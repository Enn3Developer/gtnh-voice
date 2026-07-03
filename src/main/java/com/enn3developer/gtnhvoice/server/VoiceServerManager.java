package com.enn3developer.gtnhvoice.server;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdp;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.PingPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceEndPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
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
    private static final long ROUTING_LOG_THROTTLE_MILLIS = 500;
    private static final long NO_SNAPSHOT_LOG_THROTTLE_MILLIS = 5000;

    private final Map<UUID, VoiceServerSession> sessionsBySecret = new ConcurrentHashMap<>();
    private final Map<UUID, VoiceServerSession> sessionsByPlayerUuid = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> pendingSends = new ConcurrentLinkedQueue<>();
    private final Map<UUID, AtomicLong> lastRoutingLogMillis = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> lastNoSnapshotLogMillis = new ConcurrentHashMap<>();

    /**
     * Position/dimension snapshot of every online player, rebuilt wholesale each server tick (see
     * {@link #refreshPositionSnapshot()}) and read from the UDP/Netty thread during routing. The
     * reference swap (rather than mutating in place) means readers never observe a half-built map.
     */
    private volatile Map<UUID, PlayerSnapshot> positionSnapshot = Collections.emptyMap();

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
        lastRoutingLogMillis.clear();
        lastNoSnapshotLogMillis.clear();
        positionSnapshot = Collections.emptyMap();
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
            Packet<?> packet = packetUdp.getPacketUntyped(session.getEncryption());
            if (packet instanceof PingPacket) {
                // Liveness/NAT keepalive only for now - last-seen is already updated by touch() above.
            } else if (packet instanceof PlayerAudioPacket) {
                routeAudio(session, (PlayerAudioPacket) packet);
            }
        } catch (Exception e) {
            GtnhVoice.LOG.error(
                "Failed to read UDP packet from {} (secret {})",
                sender,
                VoiceProtocol.abbreviateSecret(session.getSecret()),
                e);
        }
    }

    /**
     * Proximity-routes one inbound frame of speaker audio to every eligible listener. Runs on the
     * UDP/Netty thread - reads only {@link #positionSnapshot} and the concurrent session maps,
     * never touches live world/entity state.
     */
    private void routeAudio(@NotNull VoiceServerSession speakerSession, @NotNull PlayerAudioPacket audio) {
        UdpTransportServer server = udpServer;
        if (server == null) return;

        UUID speakerUuid = speakerSession.getPlayerUuid();
        Map<UUID, PlayerSnapshot> snapshot = positionSnapshot;
        PlayerSnapshot speakerPos = snapshot.get(speakerUuid);
        if (speakerPos == null) {
            logNoSnapshotThrottled(speakerSession);
            return;
        }

        int cutoff = Math.min(Config.distance, Config.maxDistance);
        boolean maxDistanceIsBinding = Config.maxDistance < Config.distance;

        List<String> recipients = new ArrayList<>();
        List<String> excluded = new ArrayList<>();

        for (VoiceServerSession recipientSession : sessionsByPlayerUuid.values()) {
            String name = recipientSession.getPlayerName();

            if (recipientSession.getPlayerUuid()
                .equals(speakerUuid)) {
                excluded.add(name + "(self)");
                continue;
            }

            InetSocketAddress recipientAddress = recipientSession.getLastAddress();
            if (recipientAddress == null) {
                excluded.add(name + "(no-udp)");
                continue;
            }

            PlayerSnapshot recipientPos = snapshot.get(recipientSession.getPlayerUuid());
            if (recipientPos == null) {
                excluded.add(name + "(no-snapshot)");
                continue;
            }

            if (recipientPos.getDimensionId() != speakerPos.getDimensionId()) {
                excluded.add(name + "(other-dim=" + recipientPos.getDimensionId() + ")");
                continue;
            }

            double distance = speakerPos.distanceTo(recipientPos);
            if (distance > cutoff) {
                excluded.add(
                    String.format(
                        "%s(out-of-range:%.1fm>%s cutoff %dm)",
                        name,
                        distance,
                        maxDistanceIsBinding ? "maxDistance" : "distance",
                        cutoff));
                continue;
            }

            SourceAudioPacket forward = new SourceAudioPacket(
                audio.getSequenceNumber(),
                (byte) 0,
                audio.getData(),
                speakerUuid,
                speakerPos.getX(),
                speakerPos.getY(),
                speakerPos.getZ());
            server.send(forward, recipientSession.getSecret(), recipientSession.getEncryption(), recipientAddress);
            recipients.add(String.format("%s@%.1fm", name, distance));
        }

        logRoutingThrottled(speakerSession, speakerPos, recipients, excluded);
    }

    private void logRoutingThrottled(@NotNull VoiceServerSession speakerSession, @NotNull PlayerSnapshot speakerPos,
        @NotNull List<String> recipients, @NotNull List<String> excluded) {
        AtomicLong last = lastRoutingLogMillis.computeIfAbsent(speakerSession.getPlayerUuid(), id -> new AtomicLong());
        long now = System.currentTimeMillis();
        long previous = last.get();
        if (now - previous < ROUTING_LOG_THROTTLE_MILLIS || !last.compareAndSet(previous, now)) return;

        GtnhVoice.LOG.info(
            "routing from {} pos({}, {}, {}) dim={} -> recipients [{}] excluded [{}]",
            speakerSession.getPlayerName(),
            String.format("%.1f", speakerPos.getX()),
            String.format("%.1f", speakerPos.getY()),
            String.format("%.1f", speakerPos.getZ()),
            speakerPos.getDimensionId(),
            String.join(", ", recipients),
            String.join(", ", excluded));
    }

    private void logNoSnapshotThrottled(@NotNull VoiceServerSession speakerSession) {
        AtomicLong last = lastNoSnapshotLogMillis
            .computeIfAbsent(speakerSession.getPlayerUuid(), id -> new AtomicLong());
        long now = System.currentTimeMillis();
        long previous = last.get();
        if (now - previous < NO_SNAPSHOT_LOG_THROTTLE_MILLIS || !last.compareAndSet(previous, now)) return;

        GtnhVoice.LOG.warn(
            "Dropped audio frame from {}: no position snapshot yet (just joined?)",
            speakerSession.getPlayerName());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        refreshPositionSnapshot();

        Runnable task;
        while ((task = pendingSends.poll()) != null) {
            task.run();
        }
    }

    /**
     * Rebuilds {@link #positionSnapshot} wholesale from live {@code EntityPlayerMP} state. Only
     * ever called on the server thread (from {@link #onServerTick}) - this is the sole place world
     * state is touched.
     */
    private void refreshPositionSnapshot() {
        MinecraftServer server = FMLCommonHandler.instance()
            .getMinecraftServerInstance();
        if (server == null) return;

        List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
        Map<UUID, PlayerSnapshot> next = new HashMap<>(players.size());
        for (EntityPlayerMP player : players) {
            UUID uuid = player.getGameProfile()
                .getId();
            next.put(
                uuid,
                new PlayerSnapshot(
                    uuid,
                    player.getCommandSenderName(),
                    player.posX,
                    player.posY,
                    player.posZ,
                    player.dimension));
        }

        positionSnapshot = next;
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerUuid = event.player.getGameProfile()
            .getId();
        VoiceServerSession session = sessionsByPlayerUuid.remove(playerUuid);
        if (session != null) {
            sessionsBySecret.remove(session.getSecret());
            lastRoutingLogMillis.remove(playerUuid);
            lastNoSnapshotLogMillis.remove(playerUuid);
            GtnhVoice.LOG.info("Voice session ended for {} (logout)", session.getPlayerName());
            broadcastSourceEnd(session);
        }
    }

    /**
     * Tells every remaining voice session that {@code endedSession}'s speaker is gone, so clients
     * can tear down its {@code VoiceSource} (client-side handling is a later tranche - this only
     * emits the signal).
     */
    private void broadcastSourceEnd(@NotNull VoiceServerSession endedSession) {
        UdpTransportServer server = udpServer;
        if (server == null) return;

        SourceEndPacket packet = new SourceEndPacket(endedSession.getPlayerUuid());

        int sentTo = 0;
        for (VoiceServerSession recipientSession : sessionsByPlayerUuid.values()) {
            InetSocketAddress recipientAddress = recipientSession.getLastAddress();
            if (recipientAddress == null) continue;

            server.send(packet, recipientSession.getSecret(), recipientSession.getEncryption(), recipientAddress);
            sentTo++;
        }

        GtnhVoice.LOG.info(
            "Emitted SourceEnd for {} (sourceId={}) to {} voice session(s)",
            endedSession.getPlayerName(),
            endedSession.getPlayerUuid(),
            sentTo);
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
                lastRoutingLogMillis.remove(session.getPlayerUuid());
                lastNoSnapshotLogMillis.remove(session.getPlayerUuid());
                GtnhVoice.LOG.info(
                    "Reaped stale voice session for {} (secret {}, no traffic for {}ms)",
                    session.getPlayerName(),
                    VoiceProtocol.abbreviateSecret(session.getSecret()),
                    now - session.getLastSeenMillis());
            }
        }
    }
}
