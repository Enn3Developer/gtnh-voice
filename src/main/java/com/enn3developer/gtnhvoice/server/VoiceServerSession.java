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

    private volatile InetSocketAddress lastAddress;
    private volatile long lastSeenMillis;

    public VoiceServerSession(@NotNull UUID playerUuid, @NotNull String playerName, @NotNull UUID sessionId,
        @NotNull AesEncryption encryption) {
        this(playerUuid, playerName, sessionId, encryption, new byte[0], new byte[0]);
    }

    public VoiceServerSession(@NotNull UUID playerUuid, @NotNull String playerName, @NotNull UUID sessionId,
        @NotNull AesEncryption encryption, @NotNull byte[] serverPublicKey, @NotNull byte[] clientPublicKey) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.sessionId = sessionId;
        this.encryption = encryption;
        this.serverPublicKey = serverPublicKey;
        this.clientPublicKey = clientPublicKey;
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
     * Records a datagram from this session's secret, updating last-seen and, if the source
     * address changed (first packet, or the player's UDP stream resumed from a different port),
     * re-learning it transparently without requiring a new handshake.
     */
    public void touch(@NotNull InetSocketAddress address) {
        lastSeenMillis = System.currentTimeMillis();

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
