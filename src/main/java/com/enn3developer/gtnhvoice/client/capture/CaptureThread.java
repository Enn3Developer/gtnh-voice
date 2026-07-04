package com.enn3developer.gtnhvoice.client.capture;

import java.nio.ShortBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.system.MemoryUtil;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.audio.AudioDeviceUtil;
import com.enn3developer.gtnhvoice.core.api.util.AudioUtil;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Dedicated thread that owns an OpenAL ALC11 capture device and pulls 20ms/960-sample mono 16-bit PCM frames off it,
 * handing them to a queue for a future consumer to pull from.
 * <p>
 * Must run on its own thread, never the client/render thread: {@code alcCaptureSamples} is polled in a spin/sleep
 * loop and blocking OpenAL calls have no place on the render thread. That includes muting: {@link #setMuted(boolean)}
 * only records a request from whichever thread calls it (e.g. a keybind on the client thread) - the actual
 * {@code alcCaptureStop}/{@code alcCaptureStart} calls happen inside this thread's own loop, since only the thread
 * that owns the device may touch its ALC calls. While muted the loop idles with a short sleep instead of polling
 * {@code ALC_CAPTURE_SAMPLES}, so it never mistakes "no samples because muted" for an error and never exits.
 */
@Lwjgl3Aware
public class CaptureThread extends Thread {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_SIZE = 960; // 20ms @ 48kHz mono
    private static final int DEVICE_BUFFER_SIZE = SAMPLE_RATE; // 1s of headroom between polls
    private static final long POLL_INTERVAL_MILLIS = 5L;
    private static final long LOG_INTERVAL_MILLIS = 500L;

    private final BlockingQueue<short[]> frameQueue;
    private final String deviceName;
    private final boolean startMuted;

    private volatile boolean running = true;
    private volatile boolean openedSuccessfully = false;
    private volatile boolean muteRequested;

    public CaptureThread(BlockingQueue<short[]> frameQueue, String deviceName, boolean startMuted) {
        super("gtnhvoice-capture");
        this.frameQueue = frameQueue;
        this.deviceName = deviceName;
        this.startMuted = startMuted;
        this.muteRequested = startMuted;
        setDaemon(true);
    }

    public boolean didOpenSuccessfully() {
        return openedSuccessfully;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    /**
     * Requests a mute-state change, applied on the capture thread's own loop via {@code
     * alcCaptureStop}/{@code alcCaptureStart} - never called directly from the requesting thread, since only the
     * capture thread may touch the ALC device it owns. Safe to call from any thread (e.g. a keybind handler on
     * the client thread).
     */
    public void setMuted(boolean muted) {
        muteRequested = muted;
    }

    @Override
    public void run() {
        logAvailableDevices();

        long device = openCaptureDevice(deviceName);
        if (device == MemoryUtil.NULL) {
            GtnhVoice.LOG.error(
                "[Capture] Failed to open capture device (no microphone, permission denied, or unsupported format)");
            return;
        }

        if (!checkError(device, "alcCaptureOpenDevice")) {
            ALC11.alcCaptureCloseDevice(device);
            return;
        }

        boolean muted = startMuted;
        if (!muted) {
            ALC11.alcCaptureStart(device);
            if (!checkError(device, "alcCaptureStart")) {
                ALC11.alcCaptureCloseDevice(device);
                return;
            }
        }

        openedSuccessfully = true;
        GtnhVoice.LOG.info(
            "[Capture] Capture started: {}Hz mono16, {} samples/frame{}",
            SAMPLE_RATE,
            FRAME_SIZE,
            muted ? " (starting muted)" : "");

        ShortBuffer frameBuffer = MemoryUtil.memAllocShort(FRAME_SIZE);
        long framesCaptured = 0;
        long lastLogTime = System.currentTimeMillis();

        try {
            while (running) {
                muted = applyMuteTransition(device, frameBuffer, muted);

                if (muted) {
                    if (!sleepPollInterval()) break;
                    continue;
                }

                int available = ALC10.alcGetInteger(device, ALC11.ALC_CAPTURE_SAMPLES);
                if (!checkError(device, "alcGetInteger(ALC_CAPTURE_SAMPLES)")) break;

                if (available < FRAME_SIZE) {
                    if (!sleepPollInterval()) break;
                    continue;
                }

                frameBuffer.clear();
                ALC11.alcCaptureSamples(device, frameBuffer, FRAME_SIZE);
                if (!checkError(device, "alcCaptureSamples")) break;

                short[] frame = new short[FRAME_SIZE];
                frameBuffer.get(frame);
                framesCaptured++;

                if (!frameQueue.offer(frame)) {
                    frameQueue.poll();
                    frameQueue.offer(frame);
                }

                long now = System.currentTimeMillis();
                if (now - lastLogTime >= LOG_INTERVAL_MILLIS) {
                    double rmsDb = AudioUtil.calculateAudioLevel(frame, 0, frame.length);
                    short peak = AudioUtil.getHighestAbsoluteSample(frame);
                    GtnhVoice.LOG.info(
                        "[Capture] frames={} lastFrameRmsDb={} lastFramePeak={}",
                        framesCaptured,
                        Math.round(rmsDb),
                        peak);
                    lastLogTime = now;
                }
            }
        } finally {
            MemoryUtil.memFree(frameBuffer);

            ALC11.alcCaptureStop(device);
            checkError(device, "alcCaptureStop");

            ALC11.alcCaptureCloseDevice(device);

            GtnhVoice.LOG.info("[Capture] Capture stopped and device closed, {} frames captured total", framesCaptured);
        }
    }

    /**
     * Applies a pending mute-state change (requested via {@link #setMuted}) on this thread, which owns the ALC
     * device: {@code alcCaptureStop} on mute, {@code alcCaptureStart} + stale-sample drain on unmute. Returns the
     * new effective mute state (unchanged if no transition was pending).
     */
    private boolean applyMuteTransition(long device, ShortBuffer frameBuffer, boolean muted) {
        boolean wantMuted = muteRequested;
        if (wantMuted == muted) return muted;

        if (wantMuted) {
            ALC11.alcCaptureStop(device);
            checkError(device, "alcCaptureStop (mute)");
            GtnhVoice.LOG.info("[Capture] Mic muted: alcCaptureStop issued on capture thread");
            return true;
        }

        ALC11.alcCaptureStart(device);
        checkError(device, "alcCaptureStart (unmute)");
        int drained = drainStaleSamples(device, frameBuffer);
        GtnhVoice.LOG
            .info("[Capture] Mic unmuted: alcCaptureStart issued on capture thread, drained {} stale samples", drained);
        return false;
    }

    /** Sleeps one poll interval; returns {@code false} if interrupted, signalling the loop to exit. */
    private static boolean sleepPollInterval() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /**
     * Discards whatever samples are sitting in the device's capture buffer - leftover from before {@code
     * alcCaptureStop}, since stopping does not clear it - so the first frame handed to {@link #frameQueue} after
     * unmuting is fresh audio, not a stale fragment or a stop/start click. Reuses {@code scratchBuffer} ({@link
     * #FRAME_SIZE} capacity) in chunks since {@code alcCaptureSamples} needs a buffer at least as large as the
     * sample count requested.
     */
    private int drainStaleSamples(long device, ShortBuffer scratchBuffer) {
        int drained = 0;
        int available = ALC10.alcGetInteger(device, ALC11.ALC_CAPTURE_SAMPLES);
        while (available > 0) {
            int toDrain = Math.min(available, FRAME_SIZE);
            scratchBuffer.clear();
            ALC11.alcCaptureSamples(device, scratchBuffer, toDrain);
            if (!checkError(device, "alcCaptureSamples (drain)")) break;

            drained += toDrain;
            available -= toDrain;
        }
        return drained;
    }

    private void logAvailableDevices() {
        List<String> devices = AudioDeviceUtil.listInputDevices();
        if (devices.isEmpty()) {
            GtnhVoice.LOG.warn("[Capture] No capture devices enumerated (no microphone connected?)");
            return;
        }

        GtnhVoice.LOG.info("[Capture] Available capture devices:");
        for (String name : devices) {
            GtnhVoice.LOG.info("[Capture]   - {}", name);
        }
    }

    /**
     * Opens {@code requestedDevice} by name, or the system default if {@code null}. Falls back to the default
     * device (logging a warning, never crashing) if a named device fails to open - e.g. it was unplugged since
     * last selected.
     */
    private long openCaptureDevice(String requestedDevice) {
        if (requestedDevice == null) {
            String defaultDevice = AudioDeviceUtil.defaultInputDevice();
            GtnhVoice.LOG.info("[Capture] Opening default capture device: {}", defaultDevice);
            return ALC11.alcCaptureOpenDevice((String) null, SAMPLE_RATE, AL10.AL_FORMAT_MONO16, DEVICE_BUFFER_SIZE);
        }

        GtnhVoice.LOG.info("[Capture] Opening capture device: {}", requestedDevice);
        long device = ALC11
            .alcCaptureOpenDevice(requestedDevice, SAMPLE_RATE, AL10.AL_FORMAT_MONO16, DEVICE_BUFFER_SIZE);
        if (device != MemoryUtil.NULL) return device;

        GtnhVoice.LOG
            .warn("[Capture] Failed to open requested capture device '{}', falling back to default", requestedDevice);
        return ALC11.alcCaptureOpenDevice((String) null, SAMPLE_RATE, AL10.AL_FORMAT_MONO16, DEVICE_BUFFER_SIZE);
    }

    private boolean checkError(long device, String context) {
        int error = ALC10.alcGetError(device);
        if (error == ALC10.ALC_NO_ERROR) return true;

        GtnhVoice.LOG.error("[Capture] ALC error after {}: {}", context, alcErrorToString(error));
        return false;
    }

    private static String alcErrorToString(int error) {
        return switch (error) {
            case ALC10.ALC_INVALID_DEVICE -> "ALC_INVALID_DEVICE";
            case ALC10.ALC_INVALID_CONTEXT -> "ALC_INVALID_CONTEXT";
            case ALC10.ALC_INVALID_ENUM -> "ALC_INVALID_ENUM";
            case ALC10.ALC_INVALID_VALUE -> "ALC_INVALID_VALUE";
            case ALC10.ALC_OUT_OF_MEMORY -> "ALC_OUT_OF_MEMORY";
            default -> "unknown error code " + error;
        };
    }
}
