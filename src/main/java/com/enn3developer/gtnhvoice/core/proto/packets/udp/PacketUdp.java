/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp;

import java.io.IOException;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.api.encryption.Encryption;
import com.enn3developer.gtnhvoice.core.api.encryption.EncryptionException;
import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketHandler;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;

public class PacketUdp {

    // The per-session token from the UDP header. Public (travels in cleartext) and used only for
    // server session lookup / client match - it is NOT key material. The AES key is negotiated
    // out-of-band via the X25519 handshake and never appears on the UDP leg.
    private final UUID sessionId;
    private final Packet<?> packet;

    // Sender send time (ms), prepended to the encrypted body so AES-GCM authenticates it; recovered
    // in readPacket. 0 until the packet has been decrypted.
    private long timestamp;
    private byte[] encryptedBody;
    private boolean read;

    public PacketUdp(@NotNull UUID sessionId, @NotNull Packet<?> packet, @NotNull byte[] encryptedBody) {
        this.sessionId = sessionId;
        this.packet = packet;
        this.encryptedBody = encryptedBody;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    /**
     * Authenticated sender send time (ms), recovered from the encrypted body. Meaningful only after
     * the packet has been read/decrypted (see {@link #getPacketUntyped}); the server uses it for
     * anti-replay on source-address relearning, which is why it must be authenticated, not header data.
     */
    public long getTimestamp() {
        return timestamp;
    }

    public Packet<?> getPacketUntyped(@NotNull Encryption encryption) throws IOException {
        if (!read) readPacket(encryption);

        return packet;
    }

    @SuppressWarnings("unchecked")
    public <T extends PacketHandler> Packet<T> getPacket(@NotNull Encryption encryption) throws IOException {
        if (!read) readPacket(encryption);

        return (Packet<T>) packet;
    }

    /**
     * Decrypts the packet body with the caller-supplied {@link Encryption} and reads the packet
     * fields from it. The key is not known at decode time - only once the sender's secret has been
     * resolved to a session (and thus a key) by the caller - so decryption is deferred to here.
     */
    private synchronized void readPacket(@NotNull Encryption encryption) throws IOException {
        if (encryptedBody == null) return;

        byte[] decrypted;
        try {
            decrypted = encryption.decrypt(encryptedBody);
        } catch (EncryptionException e) {
            throw new IOException("Failed to decrypt UDP packet body", e);
        }

        this.read = true;
        ByteArrayDataInput in = ByteStreams.newDataInput(decrypted);
        // Authenticated send time first, then the packet's own fields.
        this.timestamp = in.readLong();
        packet.read(in);
        this.encryptedBody = null;
    }

    @Override
    public String toString() {
        return "PacketUdp(sessionId=" + sessionId + ", timestamp=" + timestamp + ")";
    }
}
