package com.enn3developer.gtnhvoice.client;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.Tags;
import com.enn3developer.gtnhvoice.api.client.ICapturePcmFilter;
import com.enn3developer.gtnhvoice.client.capture.CaptureManager;
import com.enn3developer.gtnhvoice.client.source.VoiceSourceManager;
import com.enn3developer.gtnhvoice.core.api.audio.codec.AudioEncoder;
import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;
import com.enn3developer.gtnhvoice.core.audio.codec.opus.OpusCodecSupplier;
import com.enn3developer.gtnhvoice.core.audio.filter.rnnoise.NoiseSuppressionFilter;
import com.enn3developer.gtnhvoice.core.audio.filter.rnnoise.NoiseSuppressionFilterSupplier;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.data.audio.codec.opus.OpusMode;
import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdp;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.PingPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceEndPacket;
import com.enn3developer.gtnhvoice.core.transport.UdpTransportClient;
import com.enn3developer.gtnhvoice.network.ClientHelloPacket;
import com.enn3developer.gtnhvoice.network.NetworkHandler;
import com.enn3developer.gtnhvoice.network.ServerHelloPacket;
import com.enn3developer.gtnhvoice.network.ServerRejectPacket;
import com.enn3developer.gtnhvoice.network.VoiceGroupUpdatePacket;
import com.enn3developer.gtnhvoice.network.VoiceProtocol;
import com.enn3developer.gtnhvoice.network.VoiceRosterSnapshotPacket;
import com.enn3developer.gtnhvoice.network.VoiceRosterUpdatePacket;

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
    private static final long UNKNOWN_SESSION_LOG_THROTTLE_MILLIS = 5000L;
    private static final long READ_FAILURE_LOG_THROTTLE_MILLIS = 5000L;
    private static final int OPUS_MTU_SIZE = 1275; // max Opus frame size per RFC 6716
    private static final long WORKER_JOIN_TIMEOUT_MILLIS = 1000L;

    // ClientHello travels over the same early-connection FML channel documented in
    // ClientConnectionEventHandler's Hodgepodge dispatcher-race warning: deferring the send by one client tick
    // reduces but does not eliminate the chance it vanishes with the dispatcher unset (observed in practice with
    // two clients joining close together). Since ClientHello is idempotent server-side (repeat sends just
    // re-resolve to the same session), retry it like the existing ping until a ServerHello/ServerReject arrives.
    private static final long HANDSHAKE_RETRY_INTERVAL_MILLIS = 1000L;
    private static final int HANDSHAKE_MAX_ATTEMPTS = 10;

    private volatile VoiceClientSession session = VoiceClientSession.DISCONNECTED;
    private volatile String pendingHost;

    // The client's ephemeral X25519 keypair for the current connection attempt. Generated once in
    // onConnectedToServer and reused across every ClientHello retry, so a retried handshake re-offers
    // the same public key and completing the ECDH with any resulting ServerHello yields the same key.
    private volatile KeyPair handshakeKeyPair;
    private volatile byte[] handshakePublicKey;
    private UdpTransportClient udpClient;
    private ScheduledExecutorService pingExecutor;
    private ScheduledExecutorService handshakeExecutor;
    private final AtomicLong lastPingLogMillis = new AtomicLong();
    private final AtomicLong lastUnknownSessionLogMillis = new AtomicLong();
    private final AtomicLong lastReadFailureLogMillis = new AtomicLong();

    private volatile CaptureManager captureManager;
    private volatile ActivationGate activationGate;
    private AudioEncoder captureEncoder;
    private NoiseSuppressionFilter noiseSuppressionFilter;
    private CaptureSendWorker captureSendWorker;
    private volatile VoiceSourceManager voiceSourceManager;

    /**
     * The addon-API hook that resets durable capture chains at the start of each capture session (wired by
     * {@code ClientApiBackend.initSessionBridging}); null until then, so pre-bridging sessions simply skip it.
     */
    private volatile Runnable captureSessionResetHook;

    /**
     * The outgoing-mic PCM filter registry, alive for the singleton's whole lifetime and handed to every
     * per-session {@link CaptureSendWorker} - registrations survive disconnect/reconnect cycles without any
     * re-registration machinery. See {@link CapturePcmFilter} for the chain-position and threading contract.
     */
    private final CapturePcmFilterChain capturePcmFilterChain = new CapturePcmFilterChain();

    /**
     * The session lifecycle listener registry, alive for the singleton's whole lifetime like {@link
     * #capturePcmFilterChain} - the future addon-API dispatch layer hangs off it to re-bridge durable
     * registrations onto each fresh per-session {@link VoiceSourceManager}. See {@link VoiceSessionListener}
     * for the threading/pairing contract; started/stopping pairing is enforced inside the registry itself.
     */
    private final VoiceSessionListeners sessionListeners = new VoiceSessionListeners();

    /**
     * Client-side view of the voice roster (UUID -&gt; player name) for every other player
     * currently in voice, kept current from {@link VoiceRosterSnapshotPacket}/{@link
     * VoiceRosterUpdatePacket}. The receiving player is never present in their own roster - the
     * HUD reads local state for self. Looked up by {@link #resolveName} for the who's-talking
     * HUD and future per-player mute/volume UI.
     */
    private final Map<UUID, String> roster = new ConcurrentHashMap<>();

    /** What every player is in until the server says otherwise - the default local proximity group's label. */
    private static final String DEFAULT_GROUP_DISPLAY_NAME = "local";

    /**
     * This player's own current voice group display name, shown inside the [] of the HUD self row.
     * Server-synced via {@link VoiceGroupUpdatePacket} (on session establish and on every
     * reassignment); reverts to the default in {@link #onDisconnected}. Volatile: written from the
     * FML channel handler, read every frame by the HUD.
     */
    private volatile String groupDisplayName = DEFAULT_GROUP_DISPLAY_NAME;

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

    /**
     * Wires the shared {@link ActivationGate} in - it's registered once at mod init, independent of any
     * particular session, so push-to-talk/VA keep working across reconnects.
     */
    public void bindActivationGate(@NotNull ActivationGate activationGate) {
        this.activationGate = activationGate;
    }

    /**
     * Whether the client currently considers itself to be transmitting/speaking, per the active
     * {@link com.enn3developer.gtnhvoice.Config.ActivationMode}. {@code false} when no gate is bound yet or
     * nothing has ever been evaluated, or whenever {@link #isMuted()} - muting sits above VA/PTT, and since a
     * muted capture thread stops enqueueing frames altogether, {@link ActivationGate}'s own flag can otherwise sit
     * stale at whatever it was the instant before muting.
     */
    public boolean isSpeaking() {
        if (isMuted()) return false;

        ActivationGate gate = activationGate;
        return gate != null && gate.isSpeaking();
    }

    /**
     * Whether the mic is currently self-muted (hard mute via {@code alcCaptureStop} on the capture thread).
     * {@code false} when no capture manager is bound yet.
     */
    public boolean isMuted() {
        CaptureManager manager = captureManager;
        return manager != null && manager.isMuted();
    }

    /**
     * The session's receive-side voice sources (other players' positioned audio), or {@code null} when
     * disconnected. Read by {@link VoiceListenerTickHandler} to publish the AL listener snapshot every tick.
     */
    public @Nullable VoiceSourceManager getVoiceSourceManager() {
        return voiceSourceManager;
    }

    /**
     * The live capture-side PCM filter registry (the {@code *View()} idiom, package-private) - the seam the
     * future public addon API will wrap to register {@link CapturePcmFilter}s on outgoing mic audio.
     */
    CapturePcmFilterChain capturePcmFilterChainView() {
        return capturePcmFilterChain;
    }

    /**
     * The live session lifecycle listener registry (the {@code *View()} idiom, package-private) - the seam
     * the future addon-API dispatch layer registers through to learn when the per-session managers come up
     * and are about to die.
     */
    VoiceSessionListeners sessionListenersView() {
        return sessionListeners;
    }

    /**
     * API-backing seam for the public client API's capture PCM filters (like {@code
     * PlaybackManager#runOnAudioThread}, public only so the API backend outside this package can reach it):
     * registers {@code filter} on the durable {@link #capturePcmFilterChain}, wrapped in a per-addon isolating
     * adapter attributed to {@code addonName}. Deliberately NO session involvement, unlike the playback-side
     * seams: the chain is singleton-durable and handed to every per-session {@code CaptureSendWorker}, so one
     * attach outlives every disconnect/reconnect cycle. Returns the opaque handle
     * {@link #detachAddonCaptureFilter} takes; the API backend tracks it per bundle.
     */
    public Object attachAddonCaptureFilter(String addonName, ICapturePcmFilter filter) {
        Objects.requireNonNull(addonName, "addonName");
        Objects.requireNonNull(filter, "filter");

        AddonCaptureFilterAdapter adapter = new AddonCaptureFilterAdapter(addonName, filter);
        capturePcmFilterChain.add(adapter);
        return adapter;
    }

    /** Detaches a handle returned by {@link #attachAddonCaptureFilter}; frames simply stop flowing through. */
    public void detachAddonCaptureFilter(Object handle) {
        capturePcmFilterChain.remove((AddonCaptureFilterAdapter) Objects.requireNonNull(handle, "handle"));
    }

    /**
     * API-backing seam registering the addon-API bridging layer as a session lifecycle observer (public only
     * so {@code ClientApiBackend} outside this package can reach the package-private {@link
     * VoiceSessionListeners} registry). Both callbacks inherit the {@link VoiceSessionListener} contract: they
     * run on the session-transition thread while this manager's monitor is held, so they must stay fast and
     * non-blocking and must not re-enter session control. Not idempotent - the single caller
     * ({@code ClientApiBackend.initSessionBridging}) guards against double registration.
     */
    public void attachAddonSessionBridge(Runnable onSessionStarted, Runnable onSessionStopping) {
        Objects.requireNonNull(onSessionStarted, "onSessionStarted");
        Objects.requireNonNull(onSessionStopping, "onSessionStopping");

        sessionListeners.add(new VoiceSessionListener() {

            @Override
            public void sessionStarted() {
                onSessionStarted.run();
            }

            @Override
            public void sessionStopping() {
                onSessionStopping.run();
            }
        });
    }

    /**
     * API-backing seam registering the addon-API capture-chain reset hook (public only so {@code
     * ClientApiBackend} outside this package can reach it). Unlike {@link #attachAddonSessionBridge}'s callbacks
     * this does NOT ride the session-listener registry: the reset must run inside {@link #startCaptureSending}
     * before the fresh worker starts polling, which is earlier than {@code sessionStarted} fires. Not idempotent
     * - the single caller ({@code ClientApiBackend.initSessionBridging}) registers it exactly once.
     */
    public void attachCaptureSessionResetHook(Runnable hook) {
        captureSessionResetHook = Objects.requireNonNull(hook, "hook");
    }

    public synchronized void onConnectedToServer(@NotNull String host) {
        closeUdp();
        pendingHost = host;

        // Fresh ephemeral keypair per connection attempt (forward secrecy: it is discarded when the
        // session ends). Reused across handshake retries via the stored fields.
        handshakeKeyPair = VoiceProtocol.generateEphemeralKeyPair();
        handshakePublicKey = VoiceProtocol.encodePublicKey(handshakeKeyPair.getPublic());

        session = new VoiceClientSession(VoiceClientSession.State.CONNECTING, null, null, null, 0, (byte) 0, 0, 0);

        startHandshakeRetry(host);
    }

    public synchronized void onDisconnected() {
        closeUdp();
        session = VoiceClientSession.DISCONNECTED;
        pendingHost = null;
        roster.clear();
        groupDisplayName = DEFAULT_GROUP_DISPLAY_NAME;
        HeadIconCache.getInstance()
            .clearAll();
    }

    /**
     * Looks up a player's name by their voice sourceId (== player UUID). Backed by {@link #roster},
     * which MC 1.7.10's tab list cannot provide client-side on its own since it carries no UUIDs.
     */
    public Optional<String> resolveName(@NotNull UUID sourceId) {
        return Optional.ofNullable(roster.get(sourceId));
    }

    /**
     * Read-only view of every other player currently in voice (UUID -&gt; name), backed by the live {@link
     * #roster} map. Used by the who's-talking HUD's muted-marker row and the Players GUI's row list - never
     * includes the receiving player themselves (see {@link #roster}'s own doc).
     */
    public Map<UUID, String> getRosterView() {
        return Collections.unmodifiableMap(roster);
    }

    public synchronized void handleRosterSnapshot(@NotNull VoiceRosterSnapshotPacket packet) {
        roster.clear();
        roster.putAll(packet.getRoster());
        GtnhVoice.LOG.info("Client voice roster snapshot applied: {}", roster);
    }

    public synchronized void handleRosterUpdate(@NotNull VoiceRosterUpdatePacket packet) {
        if (packet.getMode() == VoiceRosterUpdatePacket.MODE_ADD) {
            roster.put(packet.getPlayerUuid(), packet.getPlayerName());
        } else {
            roster.remove(packet.getPlayerUuid());
            HeadIconCache.getInstance()
                .evict(packet.getPlayerUuid());
            VoiceSourceManager mgr = voiceSourceManager;
            if (mgr != null) mgr.removeSource(packet.getPlayerUuid());
        }
        GtnhVoice.LOG.info(
            "Client voice roster updated ({} {}): {}",
            packet.getMode() == VoiceRosterUpdatePacket.MODE_ADD ? "ADD" : "REMOVE",
            packet.getPlayerName(),
            roster);
    }

    /** This player's own current voice group display name for the HUD self row - see {@link #groupDisplayName}. */
    public String getGroupDisplayName() {
        return groupDisplayName;
    }

    public synchronized void handleGroupUpdate(@NotNull VoiceGroupUpdatePacket packet) {
        groupDisplayName = packet.getGroupDisplayName();
        GtnhVoice.LOG.info("Client voice group display name set to '{}'", groupDisplayName);
    }

    public synchronized void handleServerHello(@NotNull ServerHelloPacket packet) {
        UUID sessionId = packet.getSessionId();

        // The server replies with a ServerHello carrying the SAME sessionId (and the SAME server public
        // key) for every ClientHello of an already-established session (see
        // VoiceServerManager#handleClientHello's computeIfAbsent), which is exactly what makes our
        // handshake retry (see #startHandshakeRetry) safe to fire repeatedly - a retried ClientHello that
        // raced with the first ServerHello produces a harmless duplicate reply. But that's only true
        // end-to-end if we also treat a duplicate reply as a no-op here: tearing down and rebuilding an
        // already-connected UDP link/capture worker/voice sources for no reason would glitch a perfectly
        // working session.
        VoiceClientSession currentSession = session;
        if (currentSession.getState() == VoiceClientSession.State.CONNECTED
            && sessionId.equals(currentSession.getSessionId())) {
            GtnhVoice.LOG.info(
                "Ignored duplicate ServerHello for already-connected session (sessionId={})",
                VoiceProtocol.abbreviateSessionId(sessionId));
            return;
        }

        // Complete the X25519 ECDH against the server's ephemeral public key and derive the AES-256 UDP
        // key from the shared secret via HKDF. The sessionId never enters key derivation.
        KeyPair keyPair = handshakeKeyPair;
        if (keyPair == null) {
            GtnhVoice.LOG.warn("Received ServerHello with no local handshake keypair; ignoring");
            return;
        }
        byte[] key;
        try {
            PublicKey serverPublic = VoiceProtocol.decodePublicKey(packet.getPublicKey());
            byte[] sharedSecret = VoiceProtocol.computeSharedSecret(keyPair.getPrivate(), serverPublic);
            key = VoiceProtocol.deriveKey(sharedSecret);
        } catch (Exception e) {
            session = new VoiceClientSession(
                VoiceClientSession.State.DISABLED,
                "voice key exchange failed: " + e.getMessage(),
                null,
                null,
                0,
                (byte) 0,
                0,
                0);
            GtnhVoice.LOG.error("Voice key exchange failed", e);
            return;
        }
        AesEncryption encryption = new AesEncryption(key);

        String host = packet.getUdpHost()
            .isEmpty() ? pendingHost : packet.getUdpHost();

        try {
            closeUdp();

            udpClient = new UdpTransportClient(this::onUdpPacket);
            udpClient.connect(host, packet.getUdpPort());

            session = new VoiceClientSession(
                VoiceClientSession.State.CONNECTED,
                null,
                sessionId,
                encryption,
                packet.getDistance(),
                packet.getOpusMode(),
                packet.getFrameSize(),
                packet.getSampleRate());

            voiceSourceManager = new VoiceSourceManager(this::resolveName);
            voiceSourceManager.start();

            startPinging(sessionId, encryption);

            CaptureManager manager = captureManager;
            if (manager != null) {
                manager.start();
            }
            startCaptureSending(sessionId, encryption, packet.getOpusMode(), packet.getSampleRate());

            sessionListeners.fireSessionStarted();

            GtnhVoice.LOG.info(
                "Voice connected: sessionId={} keyFingerprint={} udp={}:{} distance={} opusMode={} frameSize={} sampleRate={}",
                VoiceProtocol.abbreviateSessionId(sessionId),
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

    private void startHandshakeRetry(String host) {
        AtomicInteger attempts = new AtomicInteger();

        handshakeExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "gtnhvoice-handshake");
            thread.setDaemon(true);
            return thread;
        });
        handshakeExecutor.scheduleAtFixedRate(
            () -> sendClientHello(host, attempts),
            0,
            HANDSHAKE_RETRY_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS);
    }

    /**
     * Fires on the handshake retry executor. Stops itself once the session has moved past CONNECTING (a
     * ServerHello/ServerReject arrived, or the player disconnected) - see {@link #closeUdp}, called from both
     * {@link #handleServerHello} and {@link #handleServerReject} before this method can observe the new state.
     */
    private synchronized void sendClientHello(String host, AtomicInteger attempts) {
        if (session.getState() != VoiceClientSession.State.CONNECTING) {
            stopHandshakeRetry();
            return;
        }

        int attempt = attempts.incrementAndGet();
        if (attempt > HANDSHAKE_MAX_ATTEMPTS) {
            stopHandshakeRetry();
            session = new VoiceClientSession(
                VoiceClientSession.State.DISABLED,
                "voice handshake timed out after " + HANDSHAKE_MAX_ATTEMPTS + " attempts (no ServerHello/ServerReject)",
                null,
                null,
                0,
                (byte) 0,
                0,
                0);
            GtnhVoice.LOG.warn("Voice handshake to {} timed out after {} attempts", host, HANDSHAKE_MAX_ATTEMPTS);
            return;
        }

        byte claimedVersion = Config.debugForceProtocolMismatch ? (byte) (VoiceProtocol.PROTOCOL_VERSION + 1)
            : VoiceProtocol.PROTOCOL_VERSION;
        boolean hasChannel = NetworkRegistry.INSTANCE.hasChannel(VoiceProtocol.CHANNEL, Side.CLIENT);
        NetworkHandler.WRAPPER.sendToServer(new ClientHelloPacket(claimedVersion, Tags.VERSION, handshakePublicKey));

        GtnhVoice.LOG.info(
            "Sent ClientHello to {} (attempt {}/{}, protocolVersion={}, modVersion={}, hasChannel(CLIENT)={})",
            host,
            attempt,
            HANDSHAKE_MAX_ATTEMPTS,
            claimedVersion,
            Tags.VERSION,
            hasChannel);
    }

    private void stopHandshakeRetry() {
        if (handshakeExecutor != null) {
            handshakeExecutor.shutdownNow();
            handshakeExecutor = null;
        }
    }

    private void startPinging(UUID sessionId, AesEncryption encryption) {
        pingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "gtnhvoice-ping");
            thread.setDaemon(true);
            return thread;
        });

        pingExecutor
            .scheduleAtFixedRate(() -> sendPing(sessionId, encryption), 0, PING_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void sendPing(UUID sessionId, AesEncryption encryption) {
        UdpTransportClient client = udpClient;
        if (client == null) return;

        try {
            client.send(new PingPacket(), sessionId, encryption);

            if (LogThrottle.shouldLog(lastPingLogMillis, PING_LOG_THROTTLE_MILLIS)) {
                GtnhVoice.LOG.info("Voice ping sent (sessionId={})", VoiceProtocol.abbreviateSessionId(sessionId));
            }
        } catch (Exception e) {
            GtnhVoice.LOG.error("Failed to send voice ping", e);
        }
    }

    /**
     * Real UDP receive callback, wired in on connect. Mirrors {@code VoiceServerManager.onPacket}: resolves the
     * packet against the current session's secret, decrypts, and routes the two clientbound audio packets to
     * {@link #voiceSourceManager}. Runs on the Netty IO thread.
     */
    private void onUdpPacket(@NotNull PacketUdp packetUdp, @NotNull InetSocketAddress sender) {
        VoiceClientSession currentSession = session;
        VoiceSourceManager sourceManager = voiceSourceManager;
        if (currentSession.getState() != VoiceClientSession.State.CONNECTED || sourceManager == null) return;

        if (!packetUdp.getSessionId()
            .equals(currentSession.getSessionId())) {
            if (LogThrottle.shouldLog(lastUnknownSessionLogMillis, UNKNOWN_SESSION_LOG_THROTTLE_MILLIS)) {
                GtnhVoice.LOG.warn("Dropped UDP packet with unexpected sessionId from {}", sender);
            }
            return;
        }

        try {
            Packet<?> packet = packetUdp.getPacketUntyped(currentSession.getEncryption());
            if (packet instanceof SourceAudioPacket) {
                handleSourceAudio((SourceAudioPacket) packet, sourceManager, currentSession.getDistance());
            } else if (packet instanceof SourceEndPacket) {
                sourceManager.onSourceEnd((SourceEndPacket) packet);
            }
        } catch (Exception e) {
            if (LogThrottle.shouldLog(lastReadFailureLogMillis, READ_FAILURE_LOG_THROTTLE_MILLIS)) {
                GtnhVoice.LOG.error("Failed to read voice UDP packet from {}", sender, e);
            }
        }
    }

    private void handleSourceAudio(@NotNull SourceAudioPacket audioPacket, @NotNull VoiceSourceManager sourceManager,
        int distance) {
        UUID sourceId = audioPacket.getSourceId();

        // Ingress-level mute: dropped here, before a VoiceSource is ever fed or lazily created, so a
        // muted player never decodes, never allocates AL state, and never appears as speaking.
        PlayerVoiceSettings settings = PlayerVoiceSettings.getInstance();
        if (settings.isMuted(sourceId)) {
            if (settings.markMuteDropLogged(sourceId)) {
                GtnhVoice.LOG.info("[PlayerVoice] Dropping audio from muted sourceId={}", sourceId);
            }
            return;
        }

        sourceManager.onSourceAudio(audioPacket, distance);
    }

    private void startCaptureSending(UUID sessionId, AesEncryption encryption, byte opusModeOrdinal, int sampleRate) {
        CaptureManager manager = captureManager;
        if (manager == null) {
            GtnhVoice.LOG.warn("Voice connected but no CaptureManager bound yet - mic audio will not be sent");
            return;
        }

        ActivationGate gate = activationGate;
        if (gate == null) {
            GtnhVoice.LOG.warn("Voice connected but no ActivationGate bound yet - mic audio will not be sent");
            return;
        }

        OpusMode[] modes = OpusMode.values();
        OpusMode opusMode = modes[opusModeOrdinal >= 0 && opusModeOrdinal < modes.length ? opusModeOrdinal : 0];

        // Reset any stateful chain-built capture filters before the fresh worker polls its first frame, so IIR
        // state never leaks across a disconnect/reconnect (durable raw filters are stateless no-ops here). The
        // old worker was already joined in closeUdp, so this reset strictly happens-after its last frame.
        Runnable resetHook = captureSessionResetHook;
        if (resetHook != null) resetHook.run();

        try {
            captureEncoder = OpusCodecSupplier.createEncoder(sampleRate, false, opusMode, OPUS_MTU_SIZE);
            noiseSuppressionFilter = NoiseSuppressionFilterSupplier.create(Config.denoiseEnabled)
                .orElse(null);

            captureSendWorker = new CaptureSendWorker(
                manager.getFrameQueue(),
                captureEncoder,
                noiseSuppressionFilter,
                capturePcmFilterChain,
                udpClient,
                sessionId,
                encryption,
                UUID.randomUUID(),
                gate);
            captureSendWorker.start();
        } catch (CodecException e) {
            GtnhVoice.LOG.error("Failed to open capture encoder, mic audio will not be sent", e);
        }
    }

    private void closeUdp() {
        // Before ANY teardown, so listeners can still query the live per-session managers. No-ops (inside
        // the registry) when no session was ever up - closeUdp runs unconditionally on fresh connects and
        // repeated disconnects.
        sessionListeners.fireSessionStopping();

        stopHandshakeRetry();

        if (pingExecutor != null) {
            pingExecutor.shutdownNow();
            pingExecutor = null;
        }

        if (captureSendWorker != null) {
            // Join before proceeding so the old worker is out of pcmFilterChain.apply() before the next session's
            // reset swaps any chain pipeline - otherwise an old-session frame could step the fresh pipeline, or
            // two workers step one unsynchronized pipeline concurrently. Bounded so a wedged worker never blocks
            // the disconnect path.
            captureSendWorker.shutdown();
            try {
                captureSendWorker.join(WORKER_JOIN_TIMEOUT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
            if (captureSendWorker.isAlive())
                GtnhVoice.LOG.warn("[Voice] Capture-send worker did not stop within {}ms; proceeding with teardown",
                    WORKER_JOIN_TIMEOUT_MILLIS);
            captureSendWorker = null;
        }

        if (captureManager != null) {
            captureManager.stop();
        }

        if (captureEncoder != null) {
            captureEncoder.close();
            captureEncoder = null;
        }

        if (noiseSuppressionFilter != null) {
            noiseSuppressionFilter.close();
            noiseSuppressionFilter = null;
        }

        if (voiceSourceManager != null) {
            voiceSourceManager.stop();
            voiceSourceManager = null;
        }

        if (udpClient != null) {
            udpClient.close();
            udpClient = null;
        }
    }
}
