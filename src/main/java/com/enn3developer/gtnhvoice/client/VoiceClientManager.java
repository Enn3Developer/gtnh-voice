package com.enn3developer.gtnhvoice.client;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.Tags;
import com.enn3developer.gtnhvoice.client.capture.CaptureManager;
import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.audio.codec.opus.JavaOpusEncoder;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.data.audio.codec.opus.OpusMode;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.PingPacket;
import com.enn3developer.gtnhvoice.core.transport.UdpTransportClient;
import com.enn3developer.gtnhvoice.network.ClientHelloPacket;
import com.enn3developer.gtnhvoice.network.NetworkHandler;
import com.enn3developer.gtnhvoice.network.ServerHelloPacket;
import com.enn3developer.gtnhvoice.network.ServerRejectPacket;
import com.enn3developer.gtnhvoice.network.VoiceProtocol;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.relauncher.Side;

/**
 * Client-side half of the handshake: sends {@link ClientHelloPacket} on world join, opens the UDP
 * link and starts pinging on {@link ServerHelloPacket}, or marks the session disabled on {@link
 * ServerRejectPacket}. Session state is exposed via {@link #getSession()} for a future GUI/HUD.
 */
public final class VoiceClientManager {

    private static final VoiceClientManager INSTANCE = new VoiceClientManager();

    private static final long PING_INTERVAL_MILLIS = 5000L;
    private static final long PING_LOG_THROTTLE_MILLIS = 30_000L;
    private static final int OPUS_MTU_SIZE = 1275; // max Opus frame size per RFC 6716

    private volatile VoiceClientSession session = VoiceClientSession.DISCONNECTED;
    private volatile String pendingHost;
    private UdpTransportClient udpClient;
    private ScheduledExecutorService pingExecutor;
    private final AtomicLong lastPingLogMillis = new AtomicLong();

    private volatile CaptureManager captureManager;
    private JavaOpusEncoder captureEncoder;
    private CaptureSendWorker captureSendWorker;

    public static VoiceClientManager getInstance() {
        return INSTANCE;
    }

    private VoiceClientManager() {}

    public VoiceClientSession getSession() {
        return session;
    }

    /**
     * Wires the shared {@link CaptureManager} in - once a session connects, {@link
     * #handleServerHello} drains this manager's frame queue for the session's lifetime, regardless
     * of whether the capture keybind is currently toggled on.
     */
    public void bindCaptureManager(@NotNull CaptureManager captureManager) {
        this.captureManager = captureManager;
    }

    public synchronized void onConnectedToServer(@NotNull String host) {
        closeUdp();
        pendingHost = host;
        session = new VoiceClientSession(VoiceClientSession.State.CONNECTING, null, null, null, 0, (byte) 0, 0, 0);

        byte claimedVersion = Config.debugForceProtocolMismatch ? (byte) (VoiceProtocol.PROTOCOL_VERSION + 1)
            : VoiceProtocol.PROTOCOL_VERSION;
        boolean hasChannel = NetworkRegistry.INSTANCE.hasChannel(VoiceProtocol.CHANNEL, Side.CLIENT);
        NetworkHandler.WRAPPER.sendToServer(new ClientHelloPacket(claimedVersion, Tags.VERSION));

        GtnhVoice.LOG.info(
            "Sent ClientHello to {} (protocolVersion={}, modVersion={}, hasChannel(CLIENT)={})",
            host,
            claimedVersion,
            Tags.VERSION,
            hasChannel);
    }

    public synchronized void onDisconnected() {
        closeUdp();
        session = VoiceClientSession.DISCONNECTED;
        pendingHost = null;
    }

    public synchronized void handleServerHello(@NotNull ServerHelloPacket packet) {
        UUID secret = packet.getSecret();
        byte[] key = VoiceProtocol.deriveKey(secret);
        AesEncryption encryption = new AesEncryption(key);

        String host = packet.getUdpHost()
            .isEmpty() ? pendingHost : packet.getUdpHost();

        try {
            closeUdp();

            udpClient = new UdpTransportClient((p, sender) -> {});
            udpClient.connect(host, packet.getUdpPort());

            session = new VoiceClientSession(
                VoiceClientSession.State.CONNECTED,
                null,
                secret,
                encryption,
                packet.getDistance(),
                packet.getOpusMode(),
                packet.getFrameSize(),
                packet.getSampleRate());

            startPinging(secret, encryption);
            startCaptureSending(secret, encryption, packet.getOpusMode(), packet.getSampleRate());

            GtnhVoice.LOG.info(
                "Voice connected: secret={} keyFingerprint={} udp={}:{} distance={} opusMode={} frameSize={} sampleRate={}",
                VoiceProtocol.abbreviateSecret(secret),
                VoiceProtocol.fingerprintKey(key),
                host,
                packet.getUdpPort(),
                packet.getDistance(),
                packet.getOpusMode(),
                packet.getFrameSize(),
                packet.getSampleRate());
        } catch (Exception e) {
            session = new VoiceClientSession(
                VoiceClientSession.State.DISABLED,
                "failed to open UDP: " + e.getMessage(),
                null,
                null,
                0,
                (byte) 0,
                0,
                0);
            GtnhVoice.LOG.error("Failed to open voice UDP socket to {}:{}", host, packet.getUdpPort(), e);
        }
    }

    public synchronized void handleServerReject(@NotNull ServerRejectPacket packet) {
        closeUdp();

        String reason = "incompatible (server protocol " + packet.getServerProtocolVersion()
            + ", reason code "
            + packet.getReason()
            + ")";
        session = new VoiceClientSession(VoiceClientSession.State.DISABLED, reason, null, null, 0, (byte) 0, 0, 0);

        GtnhVoice.LOG.warn("Voice disabled: {}", reason);
    }

    private void startPinging(UUID secret, AesEncryption encryption) {
        pingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "gtnhvoice-ping");
            thread.setDaemon(true);
            return thread;
        });

        pingExecutor
            .scheduleAtFixedRate(() -> sendPing(secret, encryption), 0, PING_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void sendPing(UUID secret, AesEncryption encryption) {
        UdpTransportClient client = udpClient;
        if (client == null) return;

        try {
            client.send(new PingPacket(), secret, encryption);

            long now = System.currentTimeMillis();
            long last = lastPingLogMillis.get();
            if (now - last >= PING_LOG_THROTTLE_MILLIS && lastPingLogMillis.compareAndSet(last, now)) {
                GtnhVoice.LOG.info("Voice ping sent (secret={})", VoiceProtocol.abbreviateSecret(secret));
            }
        } catch (Exception e) {
            GtnhVoice.LOG.error("Failed to send voice ping", e);
        }
    }

    private void startCaptureSending(UUID secret, AesEncryption encryption, byte opusModeOrdinal, int sampleRate) {
        CaptureManager manager = captureManager;
        if (manager == null) {
            GtnhVoice.LOG.warn("Voice connected but no CaptureManager bound yet - mic audio will not be sent");
            return;
        }

        OpusMode[] modes = OpusMode.values();
        OpusMode opusMode = modes[opusModeOrdinal >= 0 && opusModeOrdinal < modes.length ? opusModeOrdinal : 0];

        try {
            captureEncoder = new JavaOpusEncoder(sampleRate, false, opusMode, OPUS_MTU_SIZE);
            captureEncoder.open();

            captureSendWorker = new CaptureSendWorker(
                manager.getFrameQueue(),
                captureEncoder,
                udpClient,
                secret,
                encryption,
                UUID.randomUUID());
            captureSendWorker.start();
        } catch (CodecException e) {
            GtnhVoice.LOG.error("Failed to open capture encoder, mic audio will not be sent", e);
        }
    }

    private void closeUdp() {
        if (pingExecutor != null) {
            pingExecutor.shutdownNow();
            pingExecutor = null;
        }

        if (captureSendWorker != null) {
            captureSendWorker.shutdown();
            captureSendWorker = null;
        }

        if (captureEncoder != null) {
            captureEncoder.close();
            captureEncoder = null;
        }

        if (udpClient != null) {
            udpClient.close();
            udpClient = null;
        }
    }
}
