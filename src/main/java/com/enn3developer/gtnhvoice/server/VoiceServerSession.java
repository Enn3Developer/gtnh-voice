package com.enn3developer.gtnhvoice.server;

import java.net.InetSocketAddress;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.api.server.IVoiceSession;
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

    private void relearnAddress(@NotNull InetSocketAddress address) {
        InetSocketAddress previous = lastAddress;
        if (previous == null) {
            lastAddress = address;
            GtnhVoice.LOG.info(
                "session established: player {} <-> secret {} <-> {}",
                playerName,
                VoiceProtocol.abbreviateSessionId(sessionId),
                address);
        } else if (!previous.equals(address)) {
            lastAddress = address;
            GtnhVoice.LOG.info(
                "session re-learned source address: player {} <-> secret {} <-> {} (was {})",
                playerName,
                VoiceProtocol.abbreviateSessionId(sessionId),
                address,
                previous);
        }
    }
}
