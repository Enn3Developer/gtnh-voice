package com.enn3developer.gtnhvoice.server;

import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdp;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.PingPacket;
import com.enn3developer.gtnhvoice.core.transport.UdpPacketListener;
import com.enn3developer.gtnhvoice.core.transport.UdpTransportServer;
import com.enn3developer.gtnhvoice.network.ClientHelloPacket;
import com.enn3developer.gtnhvoice.network.NetworkHandler;
import com.enn3developer.gtnhvoice.network.ServerHelloPacket;
import com.enn3developer.gtnhvoice.network.ServerRejectPacket;
import com.enn3developer.gtnhvoice.network.VoiceProtocol;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Server-side lifecycle and authoritative state for voice sessions: binds the UDP transport,
 * handles the reliable-channel handshake, and maps session secrets to players. This is the
 * source of truth for identity; the UDP source address is volatile transport data re-learned from
 * every datagram (see {@link VoiceServerSession#touch}).
 * <p>
 * Zero LWJGL/OpenAL usage - this class must be safe to load and run on a dedicated server.
 * <p>
 * {@code ServerHello}/{@code ServerReject} are NOT sent synchronously from {@link
 * #handleClientHello}, which runs on the Netty IO thread while handling the just-arrived {@code
 * ClientHello}: Hodgepodge's own {@code FMLIndexedMessageToMessageCodecHook} documents that "early
 * handshake FMLProxyPackets don't have the dispatcher field set to the channel dispatcher", and its
 * fallback lookup is best-effort - a reply sent at this exact instant can silently vanish with the
 * dispatcher left null. Replies are queued and flushed on the next server tick instead.
 */
public final class VoiceServerManager implements UdpPacketListener {

    private static final VoiceServerManager INSTANCE = new VoiceServerManager();

    private static final long SESSION_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(3);
    private static final long REAP_INTERVAL_SECONDS = 30;
    private static final long UNKNOWN_SECRET_LOG_THROTTLE_MILLIS = 5000;

    private final Map<UUID, VoiceServerSession> sessionsBySecret = new ConcurrentHashMap<>();
    private final Map<UUID, VoiceServerSession> sessionsByPlayerUuid = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> pendingSends = new ConcurrentLinkedQueue<>();

    private UdpTransportServer udpServer;
    private ScheduledExecutorService reaper;
    private volatile boolean started;
    private final AtomicLong lastUnknownSecretLogMillis = new AtomicLong();

    public static VoiceServerManager getInstance() {
        return INSTANCE;
    }

    private VoiceServerManager() {}

    public synchronized void start() {
        if (started) return;

        try {
            udpServer = new UdpTransportServer(this);
            InetSocketAddress bound = udpServer.bind("0.0.0.0", Config.udpPort);

            reaper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "gtnhvoice-session-reaper");
                thread.setDaemon(true);
                return thread;
            });
            reaper.scheduleAtFixedRate(
                this::reapStaleSessions,
                REAP_INTERVAL_SECONDS,
                REAP_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

            FMLCommonHandler.instance()
                .bus()
                .register(this);

            started = true;
            GtnhVoice.LOG.info(
                "Voice UDP server bound on {} (distance={} opusMode={} frameSize={} sampleRate={})",
                bound,
                Config.distance,
                Config.getOpusMode(),
                Config.frameSize,
                Config.sampleRate);
        } catch (Exception e) {
            GtnhVoice.LOG.error("Failed to bind voice UDP server on port {}", Config.udpPort, e);
            stop();
        }
    }

    public synchronized void stop() {
        started = false;

        if (reaper != null) {
            reaper.shutdownNow();
            reaper = null;
        }

        if (udpServer != null) {
            udpServer.close();
            udpServer = null;
        }

        FMLCommonHandler.instance()
            .bus()
            .unregister(this);

        sessionsBySecret.clear();
        sessionsByPlayerUuid.clear();
        pendingSends.clear();
    }

    public void handleClientHello(@NotNull EntityPlayerMP player, @NotNull ClientHelloPacket packet) {
        if (packet.getProtocolVersion() != VoiceProtocol.PROTOCOL_VERSION) {
            pendingSends.add(() -> {
                NetworkHandler.WRAPPER.sendTo(
                    new ServerRejectPacket(VoiceProtocol.PROTOCOL_VERSION, VoiceProtocol.REASON_VERSION_MISMATCH),
                    player);
                player.addChatMessage(
                    new ChatComponentText(
                        "[GTNH Voice] Your voice mod is incompatible with this server (client protocol "
                            + packet.getProtocolVersion()
                            + ", server protocol "
                            + VoiceProtocol.PROTOCOL_VERSION
                            + "). Voice chat is disabled."));
            });
            GtnhVoice.LOG.warn(
                "Rejected voice handshake from {}: client protocol {} != server protocol {} (mod version {})",
                player.getCommandSenderName(),
                packet.getProtocolVersion(),
                VoiceProtocol.PROTOCOL_VERSION,
                packet.getModVersion());
            return;
        }

        UUID playerUuid = player.getGameProfile()
            .getId();
        VoiceServerSession session = sessionsByPlayerUuid
            .computeIfAbsent(playerUuid, id -> createSession(playerUuid, player.getCommandSenderName()));

        ServerHelloPacket hello = new ServerHelloPacket(
            VoiceProtocol.PROTOCOL_VERSION,
            session.getSecret(),
            "",
            Config.udpPort,
            Config.distance,
            (byte) Config.getOpusMode()
                .ordinal(),
            Config.frameSize,
            Config.sampleRate,
            0);
        pendingSends.add(() -> NetworkHandler.WRAPPER.sendTo(hello, player));

        GtnhVoice.LOG.info(
            "Accepted voice handshake from {}: queued ServerHello secret={} udpPort={} distance={} opusMode={} frameSize={} sampleRate={}",
            player.getCommandSenderName(),
            VoiceProtocol.abbreviateSecret(session.getSecret()),
            Config.udpPort,
            Config.distance,
            Config.getOpusMode(),
            Config.frameSize,
            Config.sampleRate);
    }

    private VoiceServerSession createSession(UUID playerUuid, String playerName) {
        UUID secret = UUID.randomUUID();
        byte[] key = VoiceProtocol.deriveKey(secret);
        AesEncryption encryption = new AesEncryption(key);

        VoiceServerSession session = new VoiceServerSession(playerUuid, playerName, secret, encryption);
        sessionsBySecret.put(secret, session);

        GtnhVoice.LOG.info(
            "Created voice session for {}: secret={} keyFingerprint={}",
            playerName,
            VoiceProtocol.abbreviateSecret(secret),
            VoiceProtocol.fingerprintKey(key));

        return session;
    }

    @Override
    public void onPacket(@NotNull PacketUdp packetUdp, @NotNull InetSocketAddress sender) {
        VoiceServerSession session = sessionsBySecret.get(packetUdp.getSecret());
        if (session == null) {
            long now = System.currentTimeMillis();
            long last = lastUnknownSecretLogMillis.get();
            if (now - last >= UNKNOWN_SECRET_LOG_THROTTLE_MILLIS
                && lastUnknownSecretLogMillis.compareAndSet(last, now)) {
                GtnhVoice.LOG.warn("Dropped UDP packet with unknown secret from {}", sender);
            }
            return;
        }

        session.touch(sender);

        try {
            Packet<?> packet = packetUdp.getPacketUntyped();
            if (packet instanceof PingPacket) {
                // Liveness/NAT keepalive only for now - last-seen is already updated by touch() above.
            }
        } catch (Exception e) {
            GtnhVoice.LOG.error(
                "Failed to read UDP packet from {} (secret {})",
                sender,
                VoiceProtocol.abbreviateSecret(session.getSecret()),
                e);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Runnable task;
        while ((task = pendingSends.poll()) != null) {
            task.run();
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerUuid = event.player.getGameProfile()
            .getId();
        VoiceServerSession session = sessionsByPlayerUuid.remove(playerUuid);
        if (session != null) {
            sessionsBySecret.remove(session.getSecret());
            GtnhVoice.LOG.info("Voice session ended for {} (logout)", session.getPlayerName());
        }
    }

    private void reapStaleSessions() {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<UUID, VoiceServerSession>> it = sessionsBySecret.entrySet()
            .iterator();
        while (it.hasNext()) {
            VoiceServerSession session = it.next()
                .getValue();
            if (now - session.getLastSeenMillis() > SESSION_TIMEOUT_MILLIS) {
                it.remove();
                sessionsByPlayerUuid.remove(session.getPlayerUuid());
                GtnhVoice.LOG.info(
                    "Reaped stale voice session for {} (secret {}, no traffic for {}ms)",
                    session.getPlayerName(),
                    VoiceProtocol.abbreviateSecret(session.getSecret()),
                    now - session.getLastSeenMillis());
            }
        }
    }
}
