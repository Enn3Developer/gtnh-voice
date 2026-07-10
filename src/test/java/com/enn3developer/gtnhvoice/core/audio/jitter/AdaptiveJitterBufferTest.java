package com.enn3developer.gtnhvoice.core.audio.jitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Drives {@link AdaptiveJitterBuffer} with a controlled fake clock (no real sleeping) under two arrival patterns:
 * steady, on-cadence packets, and jittery/out-of-order/late/duplicate packets. Asserts frames always come out in
 * non-decreasing sequence order, gaps/dupes don't crash the buffer, and the adaptive delay grows for the jittery
 * stream relative to the steady one.
 */
class AdaptiveJitterBufferTest {

    private static final int PACKET_DELAY_FRAMES = 3;
    private static final long FRAME_MILLIS = 20L;

    @Test
    void steadyArrivalEmitsFramesInOrderWithMinimalAdaptiveDelay() {
        FakeClock clock = new FakeClock();
        AdaptiveJitterBuffer buffer = new AdaptiveJitterBuffer(clock, PACKET_DELAY_FRAMES);

        int frameCount = 50;
        List<Long> emitted = new ArrayList<>();

        for (int seq = 0; seq < frameCount; seq++) {
            clock.set(seq * FRAME_MILLIS);
            buffer.offer(seq, payloadFor(seq));
            drain(buffer, clock, emitted);
        }
        // let the tail drain past the pre-buffer delay
        clock.advance(PACKET_DELAY_FRAMES * FRAME_MILLIS + 200L);
        drain(buffer, clock, emitted);

        assertEquals(frameCount, emitted.size(), "every steadily-arriving frame should eventually be emitted");
        assertInStrictlyIncreasingOrder(emitted);
        assertEquals(
            0L,
            buffer.currentTargetDelayMillis() - PACKET_DELAY_FRAMES * FRAME_MILLIS,
            "perfectly steady arrival should not add any adaptive delay on top of the base pre-buffer");
    }

    @Test
    void jitteryOutOfOrderArrivalIsHandledSafelyAndIncreasesAdaptiveDelay() {
        FakeClock clock = new FakeClock();
        AdaptiveJitterBuffer buffer = new AdaptiveJitterBuffer(clock, PACKET_DELAY_FRAMES);

        // Arrival schedule (sequence -> arrival time offset in ms) deliberately: swaps order, adds a duplicate,
        // skips a sequence number (gap), and jitters transit time around the nominal 20ms cadence.
        Random random = new Random(42);
        int frameCount = 60;
        long[] arrivalOffsets = new long[frameCount];
        long t = 0;
        for (int i = 0; i < frameCount; i++) {
            t += FRAME_MILLIS + (random.nextInt(81) - 40); // +/-40ms jitter around the 20ms cadence
            arrivalOffsets[i] = Math.max(t, 0);
        }

        List<int[]> deliveryOrder = new ArrayList<>(); // {sequenceNumber, arrivalIndex}
        for (int seq = 0; seq < frameCount; seq++) {
            if (seq == 17) continue; // gap: packet lost in transit
            deliveryOrder.add(new int[] { seq, seq });
            if (seq == 30) deliveryOrder.add(new int[] { seq, seq }); // duplicate delivery
        }
        // reorder a few adjacent pairs to simulate out-of-order UDP delivery
        swapIfPresent(deliveryOrder, 5, 6);
        swapIfPresent(deliveryOrder, 20, 21);
        swapIfPresent(deliveryOrder, 40, 41);

        List<Long> emitted = new ArrayList<>();
        assertDoesNotThrowWhileDraining(() -> {
            for (int[] delivery : deliveryOrder) {
                clock.set(arrivalOffsets[delivery[1]]);
                buffer.offer(delivery[0], payloadFor(delivery[0]));
                drain(buffer, clock, emitted);
            }
            clock.advance(PACKET_DELAY_FRAMES * FRAME_MILLIS + 2_000L);
            drain(buffer, clock, emitted);
        });

        assertFalse(emitted.isEmpty(), "jittery stream should still emit frames, not just stall forever");
        assertInNonDecreasingOrder(emitted);
        assertFalse(emitted.contains(17L), "the never-delivered sequence number should never be emitted");

        long jitteryDelay = buffer.currentTargetDelayMillis();
        assertTrue(
            jitteryDelay > PACKET_DELAY_FRAMES * FRAME_MILLIS,
            "adaptive delay should grow above the base pre-buffer under sustained jitter, was " + jitteryDelay);
    }

    @Test
    void burstOfFramesIsCappedAtMaxQueueSize() {
        // Fixed clock: nothing is ever due, so poll() never drains - every offer either enqueues or is dropped.
        AdaptiveJitterBuffer buffer = new AdaptiveJitterBuffer(() -> 0L, PACKET_DELAY_FRAMES);

        for (int i = 0; i < 100_000; i++) {
            buffer.offer(i * 1_000L, payloadFor(i));
            assertTrue(buffer.size() <= 512, "queue must never exceed the hard cap, was " + buffer.size());
        }

        assertEquals(512, buffer.size(), "a sustained burst should fill the queue exactly to its hard cap");
    }

    @Test
    void discardThroughDropsLateFramesAndPeekTracksTheHead() {
        FakeClock clock = new FakeClock();
        AdaptiveJitterBuffer buffer = new AdaptiveJitterBuffer(clock, PACKET_DELAY_FRAMES);

        buffer.offer(3, payloadFor(3));
        buffer.offer(5, payloadFor(5));
        buffer.offer(7, payloadFor(7));

        assertEquals(
            3L,
            buffer.peekSequenceNumber()
                .longValue());

        buffer.discardThrough(5);
        assertEquals(
            7L,
            buffer.peekSequenceNumber()
                .longValue(),
            "frames at or below the discard watermark must be dropped");

        buffer.discardThrough(7);
        assertTrue(buffer.isEmpty());
        assertNull(buffer.peekSequenceNumber());
    }

    @Test
    void overdueQueryTracksTheScheduleEvenForSequencesThatNeverArrived() {
        FakeClock clock = new FakeClock();
        AdaptiveJitterBuffer buffer = new AdaptiveJitterBuffer(clock, PACKET_DELAY_FRAMES);

        assertFalse(buffer.isSequenceOverdue(0), "nothing can be overdue before the buffer is anchored");

        clock.set(0);
        buffer.offer(0, payloadFor(0));
        // The adaptive delay is initialized to the base pre-buffer before the estimator warms up, so slot 0
        // becomes overdue at 2x the base delay.
        long slot0Overdue = 2L * PACKET_DELAY_FRAMES * FRAME_MILLIS;

        clock.set(slot0Overdue - 1);
        assertFalse(buffer.isSequenceOverdue(0));
        clock.set(slot0Overdue);
        assertTrue(buffer.isSequenceOverdue(0));

        // Sequence 1 was never offered, but its slot is one frame after slot 0's regardless.
        clock.set(slot0Overdue + FRAME_MILLIS - 1);
        assertFalse(buffer.isSequenceOverdue(1));
        clock.set(slot0Overdue + FRAME_MILLIS);
        assertTrue(buffer.isSequenceOverdue(1));
    }

    private static void assertDoesNotThrowWhileDraining(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException e) {
            throw new AssertionError("Buffer must not throw on gaps/dupes/reordering", e);
        }
    }

    private static void drain(AdaptiveJitterBuffer buffer, FakeClock clock, List<Long> emitted) {
        AdaptiveJitterBuffer.Frame frame;
        while ((frame = buffer.poll()) != null) {
            emitted.add(frame.sequenceNumber);
        }
    }

    private static void swapIfPresent(List<int[]> list, int seqA, int seqB) {
        int indexA = indexOfSequence(list, seqA);
        int indexB = indexOfSequence(list, seqB);
        if (indexA < 0 || indexB < 0) return;

        int[] tmp = list.get(indexA);
        list.set(indexA, list.get(indexB));
        list.set(indexB, tmp);
    }

    private static int indexOfSequence(List<int[]> list, int seq) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i)[0] == seq) return i;
        }
        return -1;
    }

    private static void assertInStrictlyIncreasingOrder(List<Long> sequenceNumbers) {
        for (int i = 1; i < sequenceNumbers.size(); i++) {
            assertTrue(
                sequenceNumbers.get(i) > sequenceNumbers.get(i - 1),
                "frames must be emitted in strictly increasing sequence order: " + sequenceNumbers);
        }
    }

    private static void assertInNonDecreasingOrder(List<Long> sequenceNumbers) {
        for (int i = 1; i < sequenceNumbers.size(); i++) {
            assertTrue(
                sequenceNumbers.get(i) >= sequenceNumbers.get(i - 1),
                "frames must be emitted in non-decreasing sequence order despite reordering/dupes: " + sequenceNumbers);
        }
    }

    private static byte[] payloadFor(int sequenceNumber) {
        return new byte[] { (byte) sequenceNumber };
    }

    private static final class FakeClock implements java.util.function.LongSupplier {

        private long now;

        void set(long value) {
            now = value;
        }

        void advance(long deltaMillis) {
            now += deltaMillis;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }
}
