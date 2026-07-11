/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.audio.jitter;

import com.enn3developer.gtnhvoice.core.audio.AudioUnit;

import java.util.Comparator;
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

    private static final int MAX_QUEUE_SIZE = 512;

    private final LongSupplier timeSupplier;
    private final AudioUnit packetDelay;
    private final Queue<Entry> queue;

    private Long firstPacketArrival;
    private Long firstSequenceNumber;
    private Long lastPacketArrival;
    private double jitterEstimate;
    private AudioUnit adaptiveDelay;

    /**
     * @param timeSupplier      clock used for both arrival timestamps and playback scheduling; must be consistent
     *                          between
     *                          the two so scheduled times are comparable to "now"
     * @param packetDelayFrames initial pre-buffer depth in 20ms frames before the adaptive delay estimate kicks in
     */
    public AdaptiveJitterBuffer(LongSupplier timeSupplier, int packetDelayFrames) {
        this.timeSupplier = timeSupplier;
        this.packetDelay = AudioUnit.frames(packetDelayFrames);
        this.adaptiveDelay = packetDelay;
        this.queue = new PriorityQueue<>(
            Math.max(2, packetDelayFrames * 2),
            Comparator.comparingLong(entry -> entry.frame.sequenceNumber));
    }

    public synchronized void offer(long sequenceNumber, byte[] data) {
        if (queue.size() >= MAX_QUEUE_SIZE) return;

        long arrivalTime = timeSupplier.getAsLong();
        long scheduledPlaybackTime = scheduledPlaybackTime(sequenceNumber, arrivalTime);
        queue.offer(new Entry(new Frame(sequenceNumber, data), scheduledPlaybackTime));
        notifyAll(); // wake a consumer blocked in awaitNextEvent - the head/schedule may have changed
    }

    private long scheduledPlaybackTime(long sequenceNumber, long arrivalTime) {
        if (lastPacketArrival != null) {
            long transit = arrivalTime - lastPacketArrival;
            // We don't carry the sender's send timestamp, so assume packets are sent at a steady 20ms rate and
            // treat any deviation in observed transit time as jitter.
            double delta = Math.abs(transit - AudioUnit.FRAME_DURATION_MILLIS);
            jitterEstimate += (delta - jitterEstimate) / 16.0;
            adaptiveDelay = AudioUnit.frames(Math.round(jitterEstimate / AudioUnit.FRAME_DURATION_MILLIS));
        }

        lastPacketArrival = arrivalTime;

        if (firstSequenceNumber == null) {
            firstPacketArrival = arrivalTime;
            firstSequenceNumber = sequenceNumber;
        }

        return slotScheduledTime(sequenceNumber);
    }

    /**
     * Anchor-relative scheduled playback time for a sequence number. Sequence numbers are remote-influenced
     * wire values (an untrusted server chooses every one, the anchor included), so the whole chain rides on
     * {@link AudioUnit}'s saturating ops: a pathological jump (e.g. {@code Long.MAX_VALUE}) that would otherwise
     * wrap into the past - reading as overdue, getting emitted, and pinning the consumer's
     * {@code lastEmittedSequence} to it, permanently deafening the listener to that speaker - clamps to the far
     * future instead and simply never comes due. Legitimate offsets compute exactly.
     */
    private long slotScheduledTime(long sequenceNumber) {
        return AudioUnit.frames(sequenceNumber)
            .sub(AudioUnit.frames(firstSequenceNumber))
            .add(packetDelay)
            .asTimestamp(firstPacketArrival);
    }

    /**
     * Returns the next frame if its scheduled playback time (plus the current adaptive delay) has been reached,
     * otherwise {@code null} - either because the buffer is empty or because the head frame isn't due yet (the
     * buffering phase).
     */
    public synchronized Frame poll() {
        Entry next = queue.peek();
        if (next == null) return null;

        if (timeSupplier.getAsLong() >= adaptiveDelay.asTimestamp(next.scheduledPlaybackTime)) {
            return queue.poll().frame;
        }

        return null;
    }

    /**
     * Sequence number of the frame at the head of the buffer - regardless of whether it's due yet - or
     * {@code null} if the buffer is empty. Lets the consumer distinguish "next frame simply isn't due" from
     * "the next slot's frame is missing while later frames already arrived" (a concealable gap).
     */
    public synchronized Long peekSequenceNumber() {
        Entry next = queue.peek();
        return next == null ? null : next.frame.sequenceNumber;
    }

    /**
     * Drops every buffered frame with sequence number at or below {@code sequenceNumber}: late arrivals and
     * duplicates whose playback slot was already played or concealed. Decoding them anyway would feed the
     * stateful Opus decoder out of order.
     */
    public synchronized void discardThrough(long sequenceNumber) {
        while (!queue.isEmpty() && queue.peek().frame.sequenceNumber <= sequenceNumber) {
            queue.poll();
        }
    }

    /**
     * Whether {@code sequenceNumber}'s scheduled playback slot (plus the current adaptive delay) has already
     * passed - whether or not a frame for it ever arrived. Always {@code false} before the buffer is anchored
     * by its first packet (or re-anchored after {@link #clear()}).
     */
    public synchronized boolean isSequenceOverdue(long sequenceNumber) {
        if (firstSequenceNumber == null) return false;

        long scheduled = adaptiveDelay.asTimestamp(slotScheduledTime(sequenceNumber));
        return timeSupplier.getAsLong() >= scheduled;
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    public synchronized int size() {
        return queue.size();
    }

    /** Current pre-buffer target: the configured base delay plus the adaptive component learned from jitter. */
    public synchronized long currentTargetDelayMillis() {
        return packetDelay.add(adaptiveDelay).asMillis();
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
        notifyAll(); // wake a consumer blocked in awaitNextEvent so it re-evaluates (e.g. a pending decoder reset)
    }

    /**
     * Blocks until the next moment the consumer could plausibly emit something: the head frame becoming due,
     * or slot {@code concealSequence} becoming overdue (pass a negative value when packet-loss concealment
     * isn't currently eligible; it's only considered while at least one frame is buffered, since concealment
     * requires a later frame as proof the stream is still flowing). Also wakes early whenever {@link #offer}
     * or {@link #clear} changes the schedule; waits indefinitely while the buffer is empty. Returns
     * immediately if the computed deadline has already passed.
     * <p>
     * The deadline is computed and waited on under this buffer's own monitor, so a packet arriving between
     * the two can never be missed (no lost-wakeup race). Note the wait itself measures real wall-clock time
     * ({@link Object#wait(long)}) while the schedule uses {@link #timeSupplier} - identical in production
     * (both {@code System.currentTimeMillis}), so don't call this with a fake clock in tests.
     */
    public synchronized void awaitNextEvent(long concealSequence) throws InterruptedException {
        Entry head = queue.peek();
        if (head == null) {
            wait();
            return;
        }

        long deadline = adaptiveDelay.asTimestamp(head.scheduledPlaybackTime);
        if (concealSequence >= 0 && firstSequenceNumber != null) {
            long concealDeadline = adaptiveDelay.asTimestamp(slotScheduledTime(concealSequence));
            deadline = Math.min(deadline, concealDeadline);
        }

        long delay = deadline - timeSupplier.getAsLong();
        if (delay <= 0) return;

        wait(delay);
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
