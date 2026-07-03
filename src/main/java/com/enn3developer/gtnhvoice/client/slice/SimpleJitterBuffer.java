package com.enn3developer.gtnhvoice.client.slice;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;

import com.enn3developer.gtnhvoice.GtnhVoice;

/**
 * Placeholder jitter buffer for the local loopback dev slice - NOT the real jitter buffer, just enough to decouple
 * network arrival timing from playback timing for this test. Buffers up to {@link #TARGET_DEPTH_FRAMES} frames
 * before it starts emitting one frame per {@link #FRAME_DURATION_MILLIS} tick to the downstream consumer; drops back
 * to buffering (instead of emitting nulls/silence) on underrun so it can re-settle instead of stuttering forever.
 * <p>
 * To be replaced by the real {@code StaticJitterBuffer} once it's lifted from Plasmo.
 */
public class SimpleJitterBuffer {

    private static final int TARGET_DEPTH_FRAMES = 3; // 60ms at 20ms/frame
    private static final int QUEUE_CAPACITY = 25; // ~0.5s headroom
    private static final long FRAME_DURATION_MILLIS = 20L;
    private static final long LOG_INTERVAL_MILLIS = 500L;

    private final BlockingQueue<short[]> buffer = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Consumer<short[]> sink;

    private volatile boolean running;
    private Thread emitterThread;

    public SimpleJitterBuffer(Consumer<short[]> sink) {
        this.sink = sink;
    }

    public void start() {
        running = true;
        emitterThread = new Thread(this::runEmitter, "gtnhvoice-jitterbuffer");
        emitterThread.setDaemon(true);
        emitterThread.start();
    }

    public void shutdown() {
        running = false;
        if (emitterThread == null) return;

        emitterThread.interrupt();
        try {
            emitterThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
        emitterThread = null;
    }

    /**
     * Pushes a freshly decoded frame into the buffer. Drops the oldest buffered frame if full, keeping added latency
     * bounded rather than growing unboundedly.
     */
    public void push(short[] frame) {
        if (!buffer.offer(frame)) {
            buffer.poll();
            buffer.offer(frame);
        }
    }

    /**
     * Drops all buffered frames, forcing the emitter back into its pre-buffering state on the next poll. Used to cut
     * a speech segment cleanly (e.g. on an inactivity timeout) instead of letting stale frames bridge into new audio.
     */
    public void clear() {
        buffer.clear();
    }

    private void runEmitter() {
        boolean started = false;
        long framesEmitted = 0;
        long underruns = 0;
        long lastLogTime = System.currentTimeMillis();

        while (running) {
            if (!started) {
                if (buffer.size() >= TARGET_DEPTH_FRAMES) started = true;
            } else {
                short[] frame = buffer.poll();
                if (frame != null) {
                    sink.accept(frame);
                    framesEmitted++;
                } else {
                    // Ran dry: go back to buffering rather than emitting silence/stuttering forever.
                    started = false;
                    underruns++;
                }
            }

            long now = System.currentTimeMillis();
            if (now - lastLogTime >= LOG_INTERVAL_MILLIS) {
                GtnhVoice.LOG.info(
                    "[JitterBuffer] buffered={} started={} framesEmitted={} underruns={}",
                    buffer.size(),
                    started,
                    framesEmitted,
                    underruns);
                lastLogTime = now;
            }

            try {
                Thread.sleep(FRAME_DURATION_MILLIS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
