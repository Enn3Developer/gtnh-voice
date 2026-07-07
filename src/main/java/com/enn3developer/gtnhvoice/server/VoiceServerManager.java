package com.enn3developer.gtnhvoice.server;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
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
import com.enn3developer.gtnhvoice.api.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.api.server.group.IGroup;
import com.enn3developer.gtnhvoice.api.server.group.RoutingContext;
import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdp;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.PingPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceEndPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.core.transport.UdpPacketListener;
import com.enn3developer.gtnhvoice.core.transport.UdpTransportServer;
import com.enn3developer.gtnhvoice.network.ClientHelloPacket;
import com.enn3developer.gtnhvoice.network.NetworkHandler;
import com.enn3developer.gtnhvoice.network.ServerHelloPacket;
import com.enn3developer.gtnhvoice.network.ServerRejectPacket;
import com.enn3developer.gtnhvoice.network.VoiceGroupUpdatePacket;
import com.enn3developer.gtnhvoice.network.VoiceProtocol;
import com.enn3developer.gtnhvoice.network.VoiceRosterSnapshotPacket;
import com.enn3developer.gtnhvoice.network.VoiceRosterUpdatePacket;
import com.enn3developer.gtnhvoice.server.group.GroupManager;

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
    private static final long UNKNOWN_SESSION_LOG_THROTTLE_MILLIS = 5000;
    private static final long READ_FAILURE_LOG_THROTTLE_MILLIS = 5000;

    private final Map<UUID, VoiceServerSession> sessionsBySessionId = new ConcurrentHashMap<>();
    private final Map<UUID, VoiceServerSession> sessionsByPlayerUuid = new ConcurrentHashMap<>();
    private final Map<UUID, VoiceServerSession> sessionsByPlayerUuidView = Collections
        .unmodifiableMap(sessionsByPlayerUuid);
    private final ConcurrentLinkedQueue<Runnable> pendingSends = new ConcurrentLinkedQueue<>();
    private final GroupManager groupManager = new GroupManager(this::onGroupAssigned);

    /**
     * Position/dimension snapshot of every online player, rebuilt wholesale each server tick (see
     * {@link #refreshPositionSnapshot()}) and read from the UDP/Netty thread during routing. The
     * reference swap (rather than mutating in place) means readers never observe a half-built map.
     */
    private volatile Map<UUID, PlayerSnapshot> positionSnapshot = Collections.emptyMap();

    private UdpTransportServer udpServer;
    private ScheduledExecutorService reaper;
    private volatile boolean started;
    private final AtomicLong lastUnknownSessionLogMillis = new AtomicLong();
    private final AtomicLong lastReadFailureLogMillis = new AtomicLong();

    public static VoiceServerManager getInstance() {
        return INSTANCE;
    }

    /**
     * The server's group manager - the single external access point for group assignment (used by
     * {@link VoiceGroupCommand}; deliberately the only manager internals exposed). Addons reach it as
     * {@link com.enn3developer.gtnhvoice.api.server.group.IGroupManager} via
     * {@link com.enn3developer.gtnhvoice.api.server.GtnhVoiceApi#groupManager()} and never import this class.
     */
    public GroupManager getGroupManager() {
        return groupManager;
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

        sessionsBySessionId.clear();
        sessionsByPlayerUuid.clear();
        pendingSends.clear();
        groupManager.clear();
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

        byte[] clientPublicKey = packet.getPublicKey();
        if (clientPublicKey == null || clientPublicKey.length != VoiceProtocol.X25519_PUBLIC_KEY_LENGTH) {
            // Protocol version matched but the ClientHello had no usable X25519 public key - a broken
            // or hostile client. Reject cleanly so it stops retrying rather than silently dropping it.
            pendingSends.add(() -> {
                NetworkHandler.WRAPPER.sendTo(
                    new ServerRejectPacket(VoiceProtocol.PROTOCOL_VERSION, VoiceProtocol.REASON_HANDSHAKE_FAILED),
                    player);
                player.addChatMessage(
                    new ChatComponentText(
                        "[GTNH Voice] Voice handshake failed (bad key exchange). Voice chat is disabled."));
            });
            GtnhVoice.LOG.warn(
                "Rejected voice handshake from {}: missing/invalid X25519 public key in ClientHello",
                player.getCommandSenderName());
            return;
        }

        UUID playerUuid = player.getGameProfile()
            .getId();

        VoiceServerSession existing = sessionsByPlayerUuid.get(playerUuid);
        boolean isNewSession = existing == null;

        VoiceServerSession session;
        if (existing != null && Arrays.equals(existing.getClientPublicKey(), clientPublicKey)) {
            // Retried ClientHello carrying the SAME ephemeral key: reuse the session so the
            // ServerHello (and thus the derived key) stays byte-identical - the client's handshake-
            // retry loop depends on this.
            session = existing;
        } else {
            // First ClientHello, or a reconnect/relog whose fresh ephemeral key raced
            // onPlayerLoggedOut (IO vs server thread): (re)run the ECDH so both sides agree on the
            // key rather than reusing a session whose key was derived against a now-gone client key.
            // A bad/low-order key surfaces as a clean reject here, not a silently broken session the
            // client would retry against until it times out.
            VoiceServerSession rebuilt;
            try {
                rebuilt = createSession(playerUuid, player.getCommandSenderName(), clientPublicKey);
            } catch (RuntimeException e) {
                pendingSends.add(() -> {
                    NetworkHandler.WRAPPER.sendTo(
                        new ServerRejectPacket(VoiceProtocol.PROTOCOL_VERSION, VoiceProtocol.REASON_HANDSHAKE_FAILED),
                        player);
                    player.addChatMessage(
                        new ChatComponentText(
                            "[GTNH Voice] Voice handshake failed (bad key exchange). Voice chat is disabled."));
                });
                GtnhVoice.LOG.warn(
                    "Rejected voice handshake from {}: X25519 key agreement failed",
                    player.getCommandSenderName(),
                    e);
                return;
            }

            VoiceServerSession previous = sessionsByPlayerUuid.put(playerUuid, rebuilt);
            if (previous != null) sessionsBySessionId.remove(previous.getSessionId());
            session = rebuilt;
        }

        if (isNewSession) {
            // Only a genuinely new player (not a retry or a same-player rebuild) re-sends the roster
            // or re-broadcasts the join.
            pendingSends.add(() -> sendRosterSnapshot(player, playerUuid));
            pendingSends.add(
                () -> broadcastRosterUpdate(VoiceRosterUpdatePacket.MODE_ADD, playerUuid, session.getPlayerName()));
            pendingSends.add(
                () -> sendGroupUpdate(
                    playerUuid,
                    groupManager.groupOf(playerUuid)
                        .getDisplayName()));
        }

        ServerHelloPacket hello = new ServerHelloPacket(
            VoiceProtocol.PROTOCOL_VERSION,
            session.getSessionId(),
            session.getServerPublicKey(),
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
            "Accepted voice handshake from {}: queued ServerHello sessionId={} udpPort={} distance={} opusMode={} frameSize={} sampleRate={}",
            player.getCommandSenderName(),
            VoiceProtocol.abbreviateSessionId(session.getSessionId()),
            Config.udpPort,
            Config.distance,
            Config.getOpusMode(),
            Config.frameSize,
            Config.sampleRate);
    }

    /**
     * (Re)creates a session for a player's ClientHello: generates the server's own ephemeral X25519
     * keypair, runs ECDH against the client's public key, and derives the AES-256 UDP key via HKDF
     * from the shared secret. Both the server's ephemeral public key and the client's public key are
     * stored on the session - the former so every retried ServerHello carries identical bytes, the
     * latter so a later ClientHello with a changed key is detected and rebuilt (see
     * {@link #handleClientHello}). Throws if the client key is invalid or yields a degenerate shared
     * secret, which the caller turns into a clean reject.
     */
    private VoiceServerSession createSession(UUID playerUuid, String playerName, byte[] clientPublicKey) {
        UUID sessionId = UUID.randomUUID();

        KeyPair serverKeyPair = VoiceProtocol.generateEphemeralKeyPair();
        PublicKey clientPublic = VoiceProtocol.decodePublicKey(clientPublicKey);
        byte[] sharedSecret = VoiceProtocol.computeSharedSecret(serverKeyPair.getPrivate(), clientPublic);
        byte[] key = VoiceProtocol.deriveKey(sharedSecret);
        AesEncryption encryption = new AesEncryption(key);
        byte[] serverPublicKey = VoiceProtocol.encodePublicKey(serverKeyPair.getPublic());

        VoiceServerSession session = new VoiceServerSession(
            playerUuid,
            playerName,
            sessionId,
            encryption,
            serverPublicKey,
            clientPublicKey);
        sessionsBySessionId.put(sessionId, session);

        GtnhVoice.LOG.info(
            "Created voice session for {}: sessionId={} keyFingerprint={}",
            playerName,
            VoiceProtocol.abbreviateSessionId(sessionId),
            VoiceProtocol.fingerprintKey(key));

        return session;
    }

    @Override
    public void onPacket(@NotNull PacketUdp packetUdp, @NotNull InetSocketAddress sender) {
        VoiceServerSession session = sessionsBySessionId.get(packetUdp.getSessionId());
        if (session == null) {
            if (LogThrottle.shouldLog(lastUnknownSessionLogMillis, UNKNOWN_SESSION_LOG_THROTTLE_MILLIS)) {
                GtnhVoice.LOG.warn("Dropped UDP packet with unknown sessionId from {}", sender);
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
            if (LogThrottle.shouldLog(lastReadFailureLogMillis, READ_FAILURE_LOG_THROTTLE_MILLIS)) {
                GtnhVoice.LOG.error(
                    "Failed to read UDP packet from {} (secret {})",
                    sender,
                    VoiceProtocol.abbreviateSessionId(session.getSessionId()),
                    e);
            }
        }
    }

    /**
     * Routes one inbound frame of speaker audio via the speaker's group (the default
     * {@link com.enn3developer.gtnhvoice.server.group.LocalGroup} unless {@link GroupManager}
     * assigned them elsewhere). Runs on the UDP/Netty thread - the {@link RoutingContext} carries
     * the whole routing event (speaker, audio, routed group, membership resolver) but hands the
     * group only {@link #positionSnapshot} and a read-only session view of live state, so groups
     * can never touch live world/entity state.
     */
    private void routeAudio(@NotNull VoiceServerSession speakerSession, @NotNull PlayerAudioPacket audio) {
        UdpTransportServer server = udpServer;
        if (server == null) return;

        IGroup group = groupManager.groupOf(speakerSession.getPlayerUuid());
        RoutingContext context = RoutingContext.builder()
            .packetSender(server::send)
            .positionSnapshot(positionSnapshot)
            .sessions(sessionsByPlayerUuidView)
            .speakerSession(speakerSession)
            .audio(audio)
            .group(group)
            .membershipResolver(groupManager::groupOf)
            .build();
        group.route(context);
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
        if (session == null) return;

        sessionsBySessionId.remove(session.getSessionId());
        GtnhVoice.LOG.info("Voice session ended for {} (logout)", session.getPlayerName());
        endSession(session);
    }

    private void endSession(VoiceServerSession session) {
        groupManager.onPlayerRemoved(session.getPlayerUuid());
        broadcastSourceEnd(session);
        pendingSends.add(() -> broadcastRosterUpdate(
            VoiceRosterUpdatePacket.MODE_REMOVE, session.getPlayerUuid(), session.getPlayerName()));
    }

    /**
     * Sends {@code recipient} the full current voice roster (every other in-voice player's
     * UUID+name, excluding {@code recipient} themselves), right after their session is
     * established. Runs from {@link #pendingSends} on the server thread, since resolving
     * recipients later touches nothing here - the roster is just the session map.
     */
    private void sendRosterSnapshot(@NotNull EntityPlayerMP recipient, @NotNull UUID excludeUuid) {
        Map<UUID, String> snapshot = new HashMap<>();
        for (VoiceServerSession s : sessionsByPlayerUuid.values()) {
            if (s.getPlayerUuid()
                .equals(excludeUuid)) continue;

            snapshot.put(s.getPlayerUuid(), s.getPlayerName());
        }

        NetworkHandler.WRAPPER
            .sendTo(new VoiceRosterSnapshotPacket(VoiceProtocol.PROTOCOL_VERSION, snapshot), recipient);

        GtnhVoice.LOG.info(
            "Sent voice roster snapshot to {} ({} entries): {}",
            recipient.getCommandSenderName(),
            snapshot.size(),
            snapshot);
    }

    /**
     * Broadcasts one roster add/remove delta to every other player currently in voice. Unlike
     * {@link #broadcastSourceEnd}, this travels over the reliable {@link NetworkHandler#WRAPPER}
     * channel rather than raw UDP, so recipients must be resolved as {@code EntityPlayerMP} - only
     * safe on the server thread, hence always queued through {@link #pendingSends}.
     */
    private void broadcastRosterUpdate(byte mode, @NotNull UUID subjectUuid, @NotNull String subjectName) {
        MinecraftServer server = FMLCommonHandler.instance()
            .getMinecraftServerInstance();
        if (server == null) return;

        VoiceRosterUpdatePacket packet = new VoiceRosterUpdatePacket(
            VoiceProtocol.PROTOCOL_VERSION,
            mode,
            subjectUuid,
            subjectName);

        List<String> recipients = new ArrayList<>();
        for (EntityPlayerMP recipient : server.getConfigurationManager().playerEntityList) {
            UUID recipientUuid = recipient.getGameProfile()
                .getId();
            if (recipientUuid.equals(subjectUuid) || !sessionsByPlayerUuid.containsKey(recipientUuid)) continue;

            NetworkHandler.WRAPPER.sendTo(packet, recipient);
            recipients.add(recipient.getCommandSenderName());
        }

        GtnhVoice.LOG.info(
            "Broadcast voice roster {} for {} ({}) to [{}]",
            mode == VoiceRosterUpdatePacket.MODE_ADD ? "ADD" : "REMOVE",
            subjectName,
            subjectUuid,
            String.join(", ", recipients));
    }

    /**
     * {@link GroupManager} assignment listener: syncs the (re)assigned player's new group display
     * name to them. Fires synchronously inside {@code assign()} on the server thread; the actual
     * send still goes through {@link #pendingSends} so it shares the roster packets' safe path (and
     * the display name is captured here, so the packet reflects the group as assigned even if a
     * later reassignment lands in the same tick's queue behind it).
     */
    private void onGroupAssigned(@NotNull UUID playerUuid, @NotNull IGroup group) {
        String displayName = group.getDisplayName();
        pendingSends.add(() -> sendGroupUpdate(playerUuid, displayName));
    }

    /**
     * Sends {@code playerUuid} their own current group display name for the HUD self row - only
     * ever the subject player, group membership is not broadcast to others. Runs from {@link
     * #pendingSends} on the server thread since it resolves an {@code EntityPlayerMP}, like {@link
     * #broadcastRosterUpdate}; skips silently if the player logged out or lost their voice session
     * by flush time.
     */
    private void sendGroupUpdate(@NotNull UUID playerUuid, @NotNull String groupDisplayName) {
        if (!sessionsByPlayerUuid.containsKey(playerUuid)) return;

        MinecraftServer server = FMLCommonHandler.instance()
            .getMinecraftServerInstance();
        if (server == null) return;

        for (EntityPlayerMP player : server.getConfigurationManager().playerEntityList) {
            if (!player.getGameProfile()
                .getId()
                .equals(playerUuid)) continue;

            NetworkHandler.WRAPPER
                .sendTo(new VoiceGroupUpdatePacket(VoiceProtocol.PROTOCOL_VERSION, groupDisplayName), player);
            GtnhVoice.LOG.info("Sent voice group update to {}: '{}'", player.getCommandSenderName(), groupDisplayName);
            return;
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

            server.send(packet, recipientSession.getSessionId(), recipientSession.getEncryption(), recipientAddress);
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

        Iterator<Map.Entry<UUID, VoiceServerSession>> it = sessionsBySessionId.entrySet()
            .iterator();
        while (it.hasNext()) {
            VoiceServerSession session = it.next()
                .getValue();
            if (now - session.getLastSeenMillis() <= SESSION_TIMEOUT_MILLIS) continue;

            it.remove();
            sessionsByPlayerUuid.remove(session.getPlayerUuid());
            GtnhVoice.LOG.info(
                "Reaped stale voice session for {} (secret {}, no traffic for {}ms)",
                session.getPlayerName(),
                VoiceProtocol.abbreviateSessionId(session.getSessionId()),
                now - session.getLastSeenMillis());
            endSession(session);
        }
    }
}
