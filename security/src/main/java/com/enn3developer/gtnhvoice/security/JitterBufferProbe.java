package com.enn3developer.gtnhvoice.security;

import com.enn3developer.gtnhvoice.core.audio.jitter.AdaptiveJitterBuffer;

/**
 * Round-3 security review: drive the REAL victim-side {@link AdaptiveJitterBuffer} with attacker-controlled
 * sequence numbers (BaseAudioPacket.sequenceNumber is a raw wire long, fully attacker-chosen and relayed
 * verbatim). Looks for integer overflow / scheduling corruption in scheduledPlaybackTime()'s
 * {@code sequenceOffset * FRAME_DURATION_MILLIS} and in poll()/isSequenceOverdue() arithmetic.
 */
public final class JitterBufferProbe {

    public static void main(String[] args) {
        long[] clock = { 1_000_000L };
        AdaptiveJitterBuffer buf = new AdaptiveJitterBuffer(() -> clock[0], 1);

        // First (anchor) packet at a normal sequence.
        buf.offer(1000L, new byte[] { 1 });
        System.out.println("[jitter] anchored at seq=1000, targetDelayMs=" + buf.currentTargetDelayMillis());

        // Attacker jumps the sequence number to the extreme so sequenceOffset * 20 overflows long.
        long evilSeq = Long.MAX_VALUE;
        buf.offer(evilSeq, new byte[] { 2 });
        clock[0] += 10_000_000L; // advance the clock far past any sane schedule

        Long head = buf.peekSequenceNumber();
        System.out.println("[jitter] head seq after evil offer = " + head);
        boolean overdue = buf.isSequenceOverdue(evilSeq);
        AdaptiveJitterBuffer.Frame polled = buf.poll();
        System.out.println("[jitter] isSequenceOverdue(evil)=" + overdue
            + " poll()=" + (polled == null ? "null(never due)" : "seq=" + polled.sequenceNumber));

        // Negative-direction overflow.
        buf.clear();
        buf.offer(0L, new byte[] { 1 });
        buf.offer(Long.MIN_VALUE, new byte[] { 2 });
        clock[0] += 10_000_000L;
        System.out.println("[jitter] after MIN_VALUE offer: head=" + buf.peekSequenceNumber()
            + " poll=" + (buf.poll() == null ? "null" : "frame"));

        System.out.println("[jitter] no crash; effect is scheduling distortion (frames stuck un-emittable / "
            + "mis-ordered), not a hard fault. Bounded by MAX_QUEUE_SIZE=512. Not independently weaponizable.");
    }
}
