package com.enn3developer.gtnhvoice.server;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.api.server.IVoiceSession;
import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.network.VoiceProtocol;

/**
 * Server-side view of one player's voice session, established over the reliable control channel.
 * The player's UUID/name and the sessionId are identity; the {@link InetSocketAddress} is volatile
 * transport data that gets re-learned from every inbound datagram, not re-established via a new
 * handshake. The sessionId is a public token (UDP header + lookup), NOT key material - the AES key
 * lives inside {@code encryption}, derived from the ephemeral X25519 handshake. Addons see this
 * only as {@link IVoiceSession} - the sessionId, encryption, and address never leave the server
 * internals.
 */
public final class VoiceServerSession implements IVoiceSession {

    // Coarse gate for the address-(re)learn logs below. relearnAddress runs inside touch(), which
    // VoiceServerManager.onPacket calls for EVERY decryptable datagram BEFORE the audio rate limiter and
    // for any packet type (Ping isn't rate-limited at all). An authenticated client rotating its UDP
    // source port with monotonic timestamps can force one relearn per packet, so without this the log is
    // 1:1 with attacker uplink (finding: 15000 pkt -> 15000 INFO lines in 5s, disk + event-loop DoS).
    // Legitimate NAT rebinds are rare, so a 5s per-session throttle loses nothing operationally.
    private static final long RELEARN_LOG_THROTTLE_MILLIS = 5000;

    // Sliding anti-replay window (IPsec-style) over audio sequence numbers. A genuine client stamps a
    // strictly-monotonic sequenceNumber (CaptureSendWorker: sequenceNumber++) inside the AES-GCM
    // authenticated body, so an attacker can't forge one - only a genuine frame or a byte-for-byte replay
    // of one carries a valid seq. The window admits any frame ahead of the highest seen and any earlier
    // frame not yet seen within WINDOW slots (tolerating normal UDP reorder), but drops an exact duplicate
    // or a frame older than the window - stopping capture-replay without dropping reordered live audio.
    private static final int AUDIO_REPLAY_WINDOW = 64;

    private final UUID playerUuid;
    private final String playerName;
    private final UUID sessionId;
    private final AesEncryption encryption;
    private final byte[] serverPublicKey;
    private final byte[] clientPublicKey;
    /**
     * The connection (login) this session belongs to, held only for identity comparison so a stale
     * logout can't tear down a session a newer connection rebuilt (see
     * {@code VoiceServerManager#onPlayerLoggedOut}). Opaque - never dereferenced. {@code null} in
     * tests that don't exercise the lifecycle.
     */
    private final Object owner;

    private volatile InetSocketAddress lastAddress;
    private volatile long lastSeenMillis;
    // Highest packet timestamp accepted for address relearning - the anti-replay watermark (see touch).
    private volatile long lastAcceptedTimestamp;
    // Per-session throttle timestamp for the address-(re)learn logs (see RELEARN_LOG_THROTTLE_MILLIS).
    private final AtomicLong lastRelearnLogMillis = new AtomicLong();

    // Audio anti-replay window state (see acceptAudioSequence, AUDIO_REPLAY_WINDOW). Guarded by
    // {@code this}: onPacket runs on the single UDP event-loop thread, but the lock keeps the window
    // self-contained and correct even if that ever changes.
    private boolean audioReplaySeen;
    private long audioReplayHighestSeq;
    private long audioReplayBitmap;

    public VoiceServerSession(@NotNull UUID playerUuid, @NotNull String playerName, @NotNull UUID sessionId,
        @NotNull AesEncryption encryption) {
        this(playerUuid, playerName, sessionId, encryption, new byte[0], new byte[0], null);
    }

    public VoiceServerSession(@NotNull UUID playerUuid, @NotNull String playerName, @NotNull UUID sessionId,
        @NotNull AesEncryption encryption, @NotNull byte[] serverPublicKey, @NotNull byte[] clientPublicKey,
        Object owner) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.sessionId = sessionId;
        this.encryption = encryption;
        this.serverPublicKey = serverPublicKey;
        this.clientPublicKey = clientPublicKey;
        this.owner = owner;
        this.lastSeenMillis = System.currentTimeMillis();
    }

    @Override
    public @NotNull UUID getPlayerUuid() {
        return playerUuid;
    }

    @Override
    public @NotNull String getPlayerName() {
        return playerName;
    }

    @Override
    public boolean hasUdpAddress() {
        return lastAddress != null;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    /** The connection this session belongs to, for identity comparison only. May be {@code null}. */
    public Object getOwner() {
        return owner;
    }

    /**
     * The server's ephemeral raw X25519 public key for this session, captured at creation so every
     * (possibly retried) ServerHello for this session carries identical bytes.
     */
    public byte[] getServerPublicKey() {
        return serverPublicKey;
    }

    /**
     * The client's ephemeral raw X25519 public key this session's key was derived against. Compared
     * on each subsequent ClientHello so a reconnect with a fresh key rebuilds the session (matching
     * keys) instead of silently reusing a stale one.
     */
    public byte[] getClientPublicKey() {
        return clientPublicKey;
    }

    public AesEncryption getEncryption() {
        return encryption;
    }

    public @Nullable InetSocketAddress getLastAddress() {
        return lastAddress;
    }

    public long getLastSeenMillis() {
        return lastSeenMillis;
    }

    /**
     * Test-only convenience for callers with no packet timestamp: relearns the address immediately,
     * bypassing (and NOT advancing) the anti-replay watermark, so it can never freeze later
     * relearning. Production must use {@link #touch(InetSocketAddress, long)} with the authenticated
     * packet timestamp.
     */
    public void touch(@NotNull InetSocketAddress address) {
        lastSeenMillis = System.currentTimeMillis();
        relearnAddress(address);
    }

    /**
     * Records an authenticated datagram from this session, updating last-seen and, if the source
     * address changed (first packet, or the player's UDP stream resumed from a different port),
     * re-learning it transparently without requiring a new handshake.
     * <p>
     * {@code packetTimestamp} is the sender-stamped send time. The source address is only relearned
     * from a packet strictly newer than the last one accepted for relearning: AES-GCM authenticates
     * a packet but does not stop a <em>replay</em> of a genuine one, so without this an on-path
     * attacker could resend a captured datagram from their own address and redirect this session's
     * inbound audio to themselves. A replay carries an old timestamp and is ignored for relearning;
     * the victim's next real packet (newer timestamp) re-adopts the correct address.
     */
    public void touch(@NotNull InetSocketAddress address, long packetTimestamp) {
        lastSeenMillis = System.currentTimeMillis();

        // Only a packet newer than the last accepted one may move the address. Reordered/duplicate
        // packets (<=) are still counted as liveness above but never relearn the address.
        if (packetTimestamp <= lastAcceptedTimestamp) return;
        lastAcceptedTimestamp = packetTimestamp;

        relearnAddress(address);
    }

    /**
     * Anti-replay gate for one audio frame's authenticated sequence number. Returns {@code true} to
     * accept (fresh frame) or {@code false} to drop (an exact replay of a frame already seen, or one
     * older than the {@link #AUDIO_REPLAY_WINDOW}-slot window). Tolerates normal UDP reordering: any
     * earlier-but-unseen frame within the window is accepted. Advances the window on every accepted
     * frame. Synchronized because the window is compound mutable state.
     */
    public synchronized boolean acceptAudioSequence(long sequenceNumber) {
        if (!audioReplaySeen) {
            audioReplaySeen = true;
            audioReplayHighestSeq = sequenceNumber;
            audioReplayBitmap = 1L;
            return true;
        }

        if (sequenceNumber > audioReplayHighestSeq) {
            long shift = sequenceNumber - audioReplayHighestSeq;
            audioReplayBitmap = shift >= AUDIO_REPLAY_WINDOW ? 1L : (audioReplayBitmap << shift) | 1L;
            audioReplayHighestSeq = sequenceNumber;
            return true;
        }

        long diff = audioReplayHighestSeq - sequenceNumber;
        if (diff >= AUDIO_REPLAY_WINDOW) return false;

        long mask = 1L << diff;
        if ((audioReplayBitmap & mask) != 0L) return false;

        audioReplayBitmap |= mask;
        return true;
    }

    private void relearnAddress(@NotNull InetSocketAddress address) {
        InetSocketAddress previous = lastAddress;
        if (previous == null) {
            lastAddress = address;
            // The relearn itself always happens; only the log is throttled. The first-ever learn always
            // wins the slot (lastRelearnLogMillis starts at 0), so a genuine session-established line
            // still prints.
            if (LogThrottle.shouldLog(lastRelearnLogMillis, RELEARN_LOG_THROTTLE_MILLIS)) {
                GtnhVoice.LOG.info(
                    "session established: player {} <-> secret {} <-> {}",
                    playerName,
                    VoiceProtocol.abbreviateSessionId(sessionId),
                    address);
            }
        } else if (!previous.equals(address)) {
            lastAddress = address;
            // Throttled: an authenticated client rotating source ports drives this per-packet ahead of
            // the audio rate limiter (see RELEARN_LOG_THROTTLE_MILLIS). The address is relearned above
            // regardless; only the log is gated.
            if (LogThrottle.shouldLog(lastRelearnLogMillis, RELEARN_LOG_THROTTLE_MILLIS)) {
                GtnhVoice.LOG.info(
                    "session re-learned source address: player {} <-> secret {} <-> {} (was {})",
                    playerName,
                    VoiceProtocol.abbreviateSessionId(sessionId),
                    address,
                    previous);
            }
        }
    }
}
