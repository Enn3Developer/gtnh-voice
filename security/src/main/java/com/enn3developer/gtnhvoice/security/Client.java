package com.enn3developer.gtnhvoice.security;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.enn3developer.gtnhvoice.core.api.encryption.Encryption;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.security.ExploitClient.LoginHandler;
import com.enn3developer.gtnhvoice.security.ExploitClient.VoiceHandshake;
import com.enn3developer.gtnhvoice.network.HelloCodec;
import com.enn3developer.gtnhvoice.network.VoiceProtocol;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * Fluent entry point to the voice-session exploit harness. Builds on {@link ExploitClient} (login +
 * FML|HS + reach PLAY) and negotiates a full voice session:
 * ClientHello -&gt; ServerHello -&gt; ECDH + HKDF -&gt; a server-accepted UDP packet.
 *
 * <p>Usage:
 * <pre>{@code
 * try (VoiceSession s = Client.connect("127.0.0.1", 25565)
 *         .username("mallory")     // optional, defaults to "VoiceBot"
 *         .establish()) {          // builds the event loop + harvests the mod list internally
 *     s.ping().sendUdp(frame).sendControl(0, body);
 * }
 * }</pre>
 *
 * <p>{@link #establish()} does <b>all</b> the plumbing internally: it creates the {@link
 * NioEventLoopGroup}, runs the STATUS-ping mod-list harvest, drives login + the FML handshake,
 * exchanges ClientHello/ServerHello, completes the ECDH + HKDF, and opens the UDP socket. The caller
 * passes no event loop and no mod list; the returned {@link VoiceSession} owns the event loop and
 * shuts it down on {@link VoiceSession#close()}. Each {@code Client} is independent, so multiple
 * actors (victim + attacker) can run concurrently in one process.
 *
 * <p>All the crypto and wire format come from the shared {@code :protocol} module ({@link
 * VoiceProtocol}, {@link AesEncryption}, {@link HelloCodec}, {@code PacketUdpCodec}) - the exact code
 * the mod runs - so the bytes this harness produces are byte-identical to the server's by
 * construction, and any protocol change breaks this at compile time. The only bespoke bits are the raw
 * MC-login/FML driver and the permissive send primitives on {@link VoiceSession}.
 */
public final class Client {

    /** The SimpleNetworkWrapper control channel (from {@link VoiceProtocol#CHANNEL}). */
    public static final String CHANNEL = VoiceProtocol.CHANNEL;
    /** The voice protocol version the mod expects (from {@link VoiceProtocol#PROTOCOL_VERSION}). */
    public static final byte PROTOCOL_VERSION = VoiceProtocol.PROTOCOL_VERSION;

    // SimpleNetworkWrapper discriminators = registration index in the mod's NetworkHandler.init(). These
    // are the mod's registration order, not a protocol wire constant, so they stay defined locally.
    static final int DISC_CLIENT_HELLO = 0;
    static final int DISC_SERVER_HELLO = 1;
    static final int DISC_SERVER_REJECT = 2;

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 25565;
    private static final String DEFAULT_USERNAME = "VoiceBot";

    private final String host;
    private final int port;
    private String username = DEFAULT_USERNAME;

    private Client(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Targets the local dev server ({@value #DEFAULT_HOST}:{@value #DEFAULT_PORT}). */
    public static Client connect() {
        return connect(DEFAULT_HOST, DEFAULT_PORT);
    }

    /** Targets {@code host:port}. */
    public static Client connect(String host, int port) {
        return new Client(host, port);
    }

    /** Offline username to log in as. Optional; defaults to {@value #DEFAULT_USERNAME}. */
    public Client username(String username) {
        this.username = username;
        return this;
    }

    /**
     * Does the whole dance and returns a live {@link VoiceSession}: creates the event loop, harvests
     * the server mod list via a STATUS ping, logs in and drives FML to PLAY, negotiates the voice
     * session (ClientHello/ServerHello, ECDH + HKDF), and opens UDP. The returned session owns the
     * event loop. Throws if the server advertises no mods or the handshake times out / is rejected.
     */
    public VoiceSession establish() throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        boolean handedOff = false;
        try {
            System.out.println("[voice] Phase 1: Server List Ping to " + host + ":" + port);
            Map<String, String> serverMods = ExploitClient.statusPing(group, host, port);
            if (serverMods.isEmpty()) {
                throw new IllegalStateException("no modinfo - server may be vanilla");
            }
            System.out.println("[voice] Server advertised " + serverMods.size() + " mods; gtnhvoice="
                + serverMods.get("gtnhvoice"));

            System.out.println("[voice] Phase 2+3: login + voice handshake as '" + username + "'");
            VoiceSession session = negotiate(group, serverMods);
            if (session == null) {
                throw new IllegalStateException("did not establish a voice session (timeout/reject)");
            }
            handedOff = true;
            return session;
        } finally {
            if (!handedOff) {
                group.shutdownGracefully();
            }
        }
    }

    /**
     * Logs in, drives FML to PLAY, negotiates the voice session and returns a live {@link
     * VoiceSession} (which takes ownership of {@code group}), or {@code null} on timeout/reject.
     */
    private VoiceSession negotiate(NioEventLoopGroup group, Map<String, String> serverMods) throws Exception {
        AtomicReference<VoiceSession> sessionRef = new AtomicReference<>();
        CountDownLatch sessionReady = new CountDownLatch(1);

        VoiceHandshake driver = new Driver(host, group, sessionRef, sessionReady);

        // Sane write-buffer watermarks so Channel.isWritable() flips well before the outbound buffer
        // grows unbounded: isWritable() goes false above HIGH and back to true below LOW. sendFramed
        // parks the (non-IO) send loop on this, giving backpressure instead of an OOM-bound queue.
        Bootstrap b = new Bootstrap();
        b.group(group)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 32 * 1024)
            .option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 128 * 1024)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    CountDownLatch reachedPlay = new CountDownLatch(1);
                    ch.pipeline()
                        .addLast(new ExploitClient.VarIntFrameDecoder());
                    ch.pipeline()
                        .addLast(new LoginHandler(host, port, username, serverMods, reachedPlay, driver));
                }
            });

        Channel ch = b.connect(host, port)
            .sync()
            .channel();

        if (!sessionReady.await(20, TimeUnit.SECONDS)) {
            System.out.println("[voice] timed out waiting for ServerHello / session");
            ch.close();
            return null;
        }
        return sessionRef.get();
    }

    /**
     * The voice handshake driver, running on the Netty IO thread. On PLAY-ready it announces the
     * gtnhvoice channel and sends ClientHello with a fresh ephemeral X25519 public key; on ServerHello
     * it completes the ECDH + HKDF, opens the UDP socket, and publishes the {@link VoiceSession}. Both
     * the hello framing and the key derivation come straight from {@code :protocol}.
     */
    private static final class Driver implements VoiceHandshake {

        private final String host;
        private final NioEventLoopGroup group;
        private final AtomicReference<VoiceSession> sessionRef;
        private final CountDownLatch sessionReady;

        private final KeyPair keyPair = VoiceProtocol.generateEphemeralKeyPair();

        Driver(String host, NioEventLoopGroup group, AtomicReference<VoiceSession> sessionRef,
            CountDownLatch sessionReady) {
            this.host = host;
            this.group = group;
            this.sessionRef = sessionRef;
            this.sessionReady = sessionReady;
        }

        @Override
        public void onPlayReady(Channel ch) {
            // Announce we speak gtnhvoice, then send ClientHello. (FML normally auto-REGISTERs its
            // NetworkRegistry channels; we do it by hand since we hand-rolled the handshake.)
            ExploitClient.sendServerboundCustomPayload(ch, "REGISTER", CHANNEL.getBytes(StandardCharsets.UTF_8));

            byte[] rawPublicKey = VoiceProtocol.encodePublicKey(keyPair.getPublic());
            byte[] body = HelloCodec.encodeClientHello(PROTOCOL_VERSION, "gtnhvoice-exploit", rawPublicKey);
            byte[] payload = new byte[1 + body.length];
            payload[0] = (byte) DISC_CLIENT_HELLO;
            System.arraycopy(body, 0, payload, 1, body.length);

            System.out.println("[voice]    -> gtnhvoice ClientHello (protocol=" + PROTOCOL_VERSION + ", pubkey="
                + rawPublicKey.length + "B)");
            ExploitClient.sendServerboundCustomPayload(ch, CHANNEL, payload);
        }

        @Override
        public void onVoicePayload(Channel ch, ByteBuf payload) {
            int disc = payload.readUnsignedByte();
            if (disc == DISC_SERVER_REJECT) {
                byte proto = payload.readByte();
                byte reason = payload.readByte();
                System.out.println("[voice] <- ServerReject (serverProtocol=" + proto + ", reason=" + reason + ")");
                sessionReady.countDown();
                return;
            }
            if (disc != DISC_SERVER_HELLO) {
                System.out.println("[voice] <- gtnhvoice payload disc=" + disc + " (ignored)");
                return;
            }
            if (sessionRef.get() != null) return; // already established (retry/dupe)

            // The remaining buffer is exactly the ServerHello body (discriminator already consumed),
            // decoded by the shared codec so it can never drift from the mod's ServerHelloPacket.
            byte[] body = new byte[payload.readableBytes()];
            payload.readBytes(body);
            HelloCodec.ServerHello hello = HelloCodec.decodeServerHello(body);
            System.out.println("[voice] <- ServerHello sessionId=" + hello.sessionId + " udpHost='" + hello.udpHost
                + "' udpPort=" + hello.udpPort + " distance=" + hello.distance + " opusMode=" + hello.opusMode
                + " frameSize=" + hello.frameSize + " sampleRate=" + hello.sampleRate);

            PublicKey peerKey = VoiceProtocol.decodePublicKey(hello.publicKey);
            byte[] aesKey = VoiceProtocol.deriveKey(VoiceProtocol.computeSharedSecret(keyPair.getPrivate(), peerKey));
            Encryption encryption = new AesEncryption(aesKey);

            String udpHost = hello.udpHost == null || hello.udpHost.isEmpty() ? host : hello.udpHost;
            InetSocketAddress udpAddr = new InetSocketAddress(udpHost, hello.udpPort);

            VoiceSession session = new VoiceSession(
                group,
                ch,
                hello.sessionId,
                aesKey,
                encryption,
                VoiceSession.openUdpSocket(),
                udpAddr,
                hello.distance,
                hello.frameSize,
                hello.sampleRate);
            sessionRef.set(session);
            sessionReady.countDown();
        }
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        String username = args.length > 2 ? args[2] : "mallory";

        try (VoiceSession s = Client.connect(host, port)
            .username(username)
            .establish()) {

            System.out.println("[voice] Session negotiated: sessionId=" + s.getSessionId()
                + " keyFingerprint=" + VoiceProtocol.fingerprintKey(s.getAesKey())
                + " udp=" + s.getUdpServer());

            // Prove it: send a valid, correctly-encrypted PingPacket. The server decrypts it, matches
            // the sessionId, and touch()es the session -> "session established: player ..." log line.
            System.out.println("[voice] -> UDP PingPacket to " + s.getUdpServer());
            s.ping()
                .ping()
                .ping();

            byte[] reply = s.receiveUdp(1500);
            if (reply != null) {
                System.out.println("[voice] <- clientbound UDP datagram (" + reply.length + " bytes)");
            } else {
                System.out.println("[voice] (no clientbound UDP reply - expected for Ping; check server log)");
            }

            System.out.println("[voice] SUCCESS: voice session established and a UDP packet sent.");
            Thread.sleep(500);
        }
    }
}
