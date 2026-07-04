package com.enn3developer.gtnhvoice.client.capture;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.enn3developer.gtnhvoice.GtnhVoice;

/**
 * Owns the lifecycle of the {@link CaptureThread} and the frame hand-off queue. In production, {@code
 * VoiceClientManager} calls {@link #start()}/{@link #stop()} for the lifetime of a voice session -
 * nothing touches OpenAL before a session connects.
 */
public class CaptureManager {

    private static final int QUEUE_CAPACITY = 50; // ~1s of 20ms frames

    private final BlockingQueue<short[]> frameQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private volatile String deviceName; // null = system default
    private volatile boolean muted; // never persisted - every new capture session starts unmuted
    private CaptureThread captureThread;

    /**
     * The queue future consumers (encoder/network pipeline) can pull 960-sample mono PCM frames from.
     */
    public BlockingQueue<short[]> getFrameQueue() {
        return frameQueue;
    }

    public boolean isCapturing() {
        return captureThread != null && captureThread.isAlive();
    }

    public String getDeviceName() {
        return deviceName;
    }

    /**
     * Whether the mic is currently self-muted (hard mute: the capture device itself has been stopped via {@code
     * alcCaptureStop}). Safe to poll from any thread, e.g. the HUD renderer.
     */
    public boolean isMuted() {
        return muted;
    }

    /**
     * Requests a mute-state change, forwarded to the live {@link CaptureThread} if capture is running - it, not
     * this manager, actually calls {@code alcCaptureStop}/{@code alcCaptureStart} since it owns the ALC device.
     * Safe to call from any thread (this is how the mute keybind reaches the capture thread). If no capture
     * thread is running yet, the request is just remembered for the next {@link #start()}.
     */
    public synchronized void setMuted(boolean muted) {
        this.muted = muted;
        if (captureThread != null) {
            captureThread.setMuted(muted);
        }
        GtnhVoice.LOG.info("[Capture] Mute requested: {} (speaking state forced false while muted)", muted);
    }

    public synchronized void start() {
        if (isCapturing()) return;

        frameQueue.clear();
        captureThread = new CaptureThread(frameQueue, deviceName, muted);
        captureThread.start();
        GtnhVoice.LOG.info("[Capture] Toggled ON (device={})", deviceName == null ? "<default>" : deviceName);
    }

    public synchronized void stop() {
        if (captureThread == null) return;

        captureThread.shutdown();
        try {
            captureThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
        captureThread = null;
        muted = false; // every new voice session starts unmuted, per design - not persisted across sessions
        GtnhVoice.LOG.info("[Capture] Toggled OFF");
    }

    /**
     * Live device hotswap (control API, driven by the settings GUI via {@code AudioDeviceController}): stops the
     * current capture device and opens the newly named one ({@code null} = system default) on a fresh {@link
     * CaptureThread}, without touching the voice session - {@link #frameQueue} is the same instance throughout, so
     * whatever's draining it (the encoder/send pipeline) never notices the swap beyond a brief gap in frames. Safe
     * to call while not currently capturing too; just records the preference for the next {@link #start()}.
     */
    public synchronized void setInputDevice(String deviceName) {
        this.deviceName = deviceName;
        if (!isCapturing()) return;

        GtnhVoice.LOG.info("[Capture] Hotswapping input device to {}", deviceName == null ? "<default>" : deviceName);
        stop();
        start();
    }
}
