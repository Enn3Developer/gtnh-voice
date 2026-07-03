package com.enn3developer.gtnhvoice.client.playback;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.enn3developer.gtnhvoice.GtnhVoice;

/**
 * Owns the lifecycle of the {@link PlaybackThread} and the frame hand-off queue. Nothing touches OpenAL until
 * {@link #start()} is called.
 */
public class PlaybackManager {

    private static final int QUEUE_CAPACITY = 50; // ~1s of 20ms frames

    private final BlockingQueue<short[]> frameQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private PlaybackThread playbackThread;

    public boolean isPlaying() {
        return playbackThread != null && playbackThread.isAlive();
    }

    public void start() {
        if (isPlaying()) return;

        frameQueue.clear();
        playbackThread = new PlaybackThread(frameQueue);
        playbackThread.start();
        GtnhVoice.LOG.info("[Playback] Started");
    }

    public void stop() {
        if (playbackThread == null) return;

        playbackThread.shutdown();
        try {
            playbackThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
        playbackThread = null;
        GtnhVoice.LOG.info("[Playback] Stopped");
    }

    /**
     * Submits a decoded 960-sample mono PCM frame for playback. Drops the oldest queued frame if the queue is full,
     * to keep playback latency bounded rather than growing unboundedly under sustained overload.
     */
    public void submit(short[] frame) {
        if (!isPlaying()) return;

        if (!frameQueue.offer(frame)) {
            frameQueue.poll();
            frameQueue.offer(frame);
        }
    }
}
