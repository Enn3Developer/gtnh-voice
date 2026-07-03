/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.audio.jitter;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.function.LongSupplier;

/**
 * Buffers raw per-source audio frames keyed by sequence number and releases them on a schedule derived from
 * observed packet arrival jitter, instead of a fixed pre-buffer depth: steady arrival collapses the extra delay
 * toward zero, bursty/jittery arrival grows it. Frames are handed out in sequence order regardless of arrival
 * order, so a caller can decode them (Opus is stateful) in the order they were actually sent.
 * <p>
 * Not thread-safe by contract of the underlying queue alone - {@link #offer}, {@link #poll} and {@link #clear} are
 * synchronized so one thread can feed packets (e.g. the network thread) while another drains them (e.g. a
 * playback-tick thread).
 */
public final class AdaptiveJitterBuffer {

    private static final long FRAME_DURATION_MILLIS = 20L;

    private final LongSupplier timeSupplier;
    private final long packetDelayMillis;
    private final Queue<Entry> queue;

    private Long firstPacketArrival;
    private Long firstSequenceNumber;
    private Long lastPacketArrival;
    private double jitterEstimate;
    private long adaptiveDelayMillis;

    /**
     * @param timeSupplier      clock used for both arrival timestamps and playback scheduling; must be consistent
     *                          between
     *                          the two so scheduled times are comparable to "now"
     * @param packetDelayFrames initial pre-buffer depth in 20ms frames before the adaptive delay estimate kicks in
     */
    public AdaptiveJitterBuffer(LongSupplier timeSupplier, int packetDelayFrames) {
        this.timeSupplier = timeSupplier;
        this.packetDelayMillis = packetDelayFrames * FRAME_DURATION_MILLIS;
        this.adaptiveDelayMillis = packetDelayMillis;
        this.queue = packetDelayFrames <= 1 ? new LinkedList<>()
            : new PriorityQueue<>(
                Math.max(2, packetDelayFrames * 2),
                Comparator.comparingLong(entry -> entry.frame.sequenceNumber));
    }

    public synchronized void offer(long sequenceNumber, byte[] data) {
        long arrivalTime = timeSupplier.getAsLong();
        long scheduledPlaybackTime = scheduledPlaybackTime(sequenceNumber, arrivalTime);
        queue.offer(new Entry(new Frame(sequenceNumber, data), scheduledPlaybackTime));
    }

    private long scheduledPlaybackTime(long sequenceNumber, long arrivalTime) {
        if (lastPacketArrival != null) {
            long transit = arrivalTime - lastPacketArrival;
            // We don't carry the sender's send timestamp, so assume packets are sent at a steady 20ms rate and
            // treat any deviation in observed transit time as jitter.
            double delta = Math.abs(transit - FRAME_DURATION_MILLIS);
            jitterEstimate += (delta - jitterEstimate) / 16.0;
            adaptiveDelayMillis = Math.round(jitterEstimate / 20.0) * 20L;
        }
        lastPacketArrival = arrivalTime;

        if (firstSequenceNumber == null) {
            firstPacketArrival = arrivalTime;
            firstSequenceNumber = sequenceNumber;
        }

        long sequenceOffset = sequenceNumber - firstSequenceNumber;
        return firstPacketArrival + packetDelayMillis + sequenceOffset * FRAME_DURATION_MILLIS;
    }

    /**
     * Returns the next frame if its scheduled playback time (plus the current adaptive delay) has been reached,
     * otherwise {@code null} - either because the buffer is empty or because the head frame isn't due yet (the
     * buffering phase).
     */
    public synchronized Frame poll() {
        Entry next = queue.peek();
        if (next == null) return null;

        if (timeSupplier.getAsLong() >= next.scheduledPlaybackTime + adaptiveDelayMillis) {
            return queue.poll().frame;
        }

        return null;
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    public synchronized int size() {
        return queue.size();
    }

    /** Current pre-buffer target: the configured base delay plus the adaptive component learned from jitter. */
    public synchronized long currentTargetDelayMillis() {
        return packetDelayMillis + adaptiveDelayMillis;
    }

    /**
     * Drops all buffered frames and resets segment anchoring so the next offered packet re-starts the buffering
     * phase from scratch. Unlike upstream (which never reuses a buffer instance across speech segments), we do -
     * so this also drops {@link #lastPacketArrival} to avoid the inactivity gap itself being misread as jitter.
     * The jitter/adaptive-delay estimate is intentionally kept: it reflects the network path's general behavior,
     * not this particular segment.
     */
    public synchronized void clear() {
        queue.clear();
        firstSequenceNumber = null;
        firstPacketArrival = null;
        lastPacketArrival = null;
    }

    public static final class Frame {

        public final long sequenceNumber;
        public final byte[] data;

        Frame(long sequenceNumber, byte[] data) {
            this.sequenceNumber = sequenceNumber;
            this.data = data;
        }
    }

    private static final class Entry {

        final Frame frame;
        final long scheduledPlaybackTime;

        Entry(Frame frame, long scheduledPlaybackTime) {
            this.frame = frame;
            this.scheduledPlaybackTime = scheduledPlaybackTime;
        }
    }
}
