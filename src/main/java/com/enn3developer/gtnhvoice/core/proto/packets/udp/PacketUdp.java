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
import com.google.common.io.ByteStreams;

public class PacketUdp {

    // The per-session token from the UDP header. Public (travels in cleartext) and used only for
    // server session lookup / client match - it is NOT key material. The AES key is negotiated
    // out-of-band via the X25519 handshake and never appears on the UDP leg.
    private final UUID sessionId;
    private final long timestamp;
    private final Packet<?> packet;

    private byte[] encryptedBody;
    private boolean read;

    public PacketUdp(@NotNull UUID sessionId, long timestamp, @NotNull Packet<?> packet,
        @NotNull byte[] encryptedBody) {
        this.sessionId = sessionId;
        this.timestamp = timestamp;
        this.packet = packet;
        this.encryptedBody = encryptedBody;
    }

    public UUID getSessionId() {
        return sessionId;
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
        packet.read(ByteStreams.newDataInput(decrypted));
        this.encryptedBody = null;
    }

    @Override
    public String toString() {
        return "PacketUdp(sessionId=" + sessionId + ", timestamp=" + timestamp + ")";
    }
}
