package com.enn3developer.gtnhvoice.client.capture;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.enn3developer.gtnhvoice.GtnhVoice;

/**
 * Owns the lifecycle of the {@link CaptureThread} and the frame hand-off queue. Lazily started/stopped by the
 * capture keybind; nothing touches OpenAL until {@link #toggle()} is called for the first time.
 */
public class CaptureManager {

    private static final int QUEUE_CAPACITY = 50; // ~1s of 20ms frames

    private final BlockingQueue<short[]> frameQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

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

    public void toggle() {
        if (isCapturing()) {
            stop();
        } else {
            start();
        }
    }

    private void start() {
        frameQueue.clear();
        captureThread = new CaptureThread(frameQueue);
        captureThread.start();
        GtnhVoice.LOG.info("[Capture] Toggled ON");
    }

    private void stop() {
        if (captureThread == null) return;

        captureThread.shutdown();
        try {
            captureThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
        captureThread = null;
        GtnhVoice.LOG.info("[Capture] Toggled OFF");
    }
}
