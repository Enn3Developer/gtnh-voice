package com.enn3developer.gtnhvoice.client.capture;

import java.nio.ShortBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALUtil;
import org.lwjgl.system.MemoryUtil;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.api.util.AudioUtil;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Dedicated thread that owns an OpenAL ALC11 capture device and pulls 20ms/960-sample mono 16-bit PCM frames off it,
 * handing them to a queue for a future consumer to pull from.
 * <p>
 * Must run on its own thread, never the client/render thread: {@code alcCaptureSamples} is polled in a spin/sleep
 * loop and blocking OpenAL calls have no place on the render thread.
 */
@Lwjgl3Aware
public class CaptureThread extends Thread {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_SIZE = 960; // 20ms @ 48kHz mono
    private static final int DEVICE_BUFFER_SIZE = SAMPLE_RATE; // 1s of headroom between polls
    private static final long POLL_INTERVAL_MILLIS = 5L;
    private static final long LOG_INTERVAL_MILLIS = 500L;

    private final BlockingQueue<short[]> frameQueue;

    private volatile boolean running = true;
    private volatile boolean openedSuccessfully = false;

    public CaptureThread(BlockingQueue<short[]> frameQueue) {
        super("gtnhvoice-capture");
        this.frameQueue = frameQueue;
        setDaemon(true);
    }

    public boolean didOpenSuccessfully() {
        return openedSuccessfully;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        logAvailableDevices();

        String defaultDevice = ALC10.alcGetString(0L, ALC11.ALC_CAPTURE_DEFAULT_DEVICE_SPECIFIER);
        GtnhVoice.LOG.info("[Capture] Opening default capture device: {}", defaultDevice);

        long device = ALC11.alcCaptureOpenDevice((String) null, SAMPLE_RATE, AL10.AL_FORMAT_MONO16, DEVICE_BUFFER_SIZE);
        if (device == MemoryUtil.NULL) {
            GtnhVoice.LOG.error(
                "[Capture] Failed to open capture device (no microphone, permission denied, or unsupported format)");
            return;
        }

        if (!checkError(device, "alcCaptureOpenDevice")) {
            ALC11.alcCaptureCloseDevice(device);
            return;
        }

        ALC11.alcCaptureStart(device);
        if (!checkError(device, "alcCaptureStart")) {
            ALC11.alcCaptureCloseDevice(device);
            return;
        }

        openedSuccessfully = true;
        GtnhVoice.LOG.info("[Capture] Capture started: {}Hz mono16, {} samples/frame", SAMPLE_RATE, FRAME_SIZE);

        ShortBuffer frameBuffer = MemoryUtil.memAllocShort(FRAME_SIZE);
        long framesCaptured = 0;
        long lastLogTime = System.currentTimeMillis();

        try {
            while (running) {
                int available = ALC10.alcGetInteger(device, ALC11.ALC_CAPTURE_SAMPLES);
                if (!checkError(device, "alcGetInteger(ALC_CAPTURE_SAMPLES)")) break;

                if (available >= FRAME_SIZE) {
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
                } else {
                    try {
                        Thread.sleep(POLL_INTERVAL_MILLIS);
                    } catch (InterruptedException e) {
                        break;
                    }
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

    private void logAvailableDevices() {
        List<String> devices = ALUtil.getStringList(0L, ALC11.ALC_CAPTURE_DEVICE_SPECIFIER);
        if (devices == null || devices.isEmpty()) {
            GtnhVoice.LOG.warn("[Capture] No capture devices enumerated (no microphone connected?)");
            return;
        }

        GtnhVoice.LOG.info("[Capture] Available capture devices:");
        for (String name : devices) {
            GtnhVoice.LOG.info("[Capture]   - {}", name);
        }
    }

    private boolean checkError(long device, String context) {
        int error = ALC10.alcGetError(device);
        if (error == ALC10.ALC_NO_ERROR) return true;

        GtnhVoice.LOG.error("[Capture] ALC error after {}: {}", context, alcErrorToString(error));
        return false;
    }

    private static String alcErrorToString(int error) {
        switch (error) {
            case ALC10.ALC_INVALID_DEVICE:
                return "ALC_INVALID_DEVICE";
            case ALC10.ALC_INVALID_CONTEXT:
                return "ALC_INVALID_CONTEXT";
            case ALC10.ALC_INVALID_ENUM:
                return "ALC_INVALID_ENUM";
            case ALC10.ALC_INVALID_VALUE:
                return "ALC_INVALID_VALUE";
            case ALC10.ALC_OUT_OF_MEMORY:
                return "ALC_OUT_OF_MEMORY";
            default:
                return "unknown error code " + error;
        }
    }
}
