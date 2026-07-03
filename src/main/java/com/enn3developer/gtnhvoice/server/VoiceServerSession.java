package com.enn3developer.gtnhvoice.server;

import java.net.InetSocketAddress;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.network.VoiceProtocol;

/**
 * Server-side view of one player's voice session, established over the reliable control channel.
 * The player's UUID/name and the secret are identity; the {@link InetSocketAddress} is volatile
 * transport data that gets re-learned from every inbound datagram, not re-established via a new
 * handshake.
 */
public final class VoiceServerSession {

    private final UUID playerUuid;
    private final String playerName;
    private final UUID secret;
    private final AesEncryption encryption;

    private volatile InetSocketAddress lastAddress;
    private volatile long lastSeenMillis;

    public VoiceServerSession(@NotNull UUID playerUuid, @NotNull String playerName, @NotNull UUID secret,
        @NotNull AesEncryption encryption) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.secret = secret;
        this.encryption = encryption;
        this.lastSeenMillis = System.currentTimeMillis();
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public UUID getSecret() {
        return secret;
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
                VoiceProtocol.abbreviateSecret(secret),
                address);
        } else if (!previous.equals(address)) {
            lastAddress = address;
            GtnhVoice.LOG.info(
                "session re-learned source address: player {} <-> secret {} <-> {} (was {})",
                playerName,
                VoiceProtocol.abbreviateSecret(secret),
                address,
                previous);
        }
    }
}
