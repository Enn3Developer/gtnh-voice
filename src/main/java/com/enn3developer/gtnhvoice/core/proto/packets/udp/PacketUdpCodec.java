/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.api.encryption.Encryption;
import com.enn3developer.gtnhvoice.core.api.encryption.EncryptionException;
import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketDirection;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketRegistry;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketUtil;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.CustomPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.PingPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SelfAudioInfoPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceEndPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

public class PacketUdpCodec {

    private static final Logger LOGGER = LogManager.getLogger(PacketUdpCodec.class);

    // magic number is used to filter packets received not from us
    private static final int MAGIC_NUMBER = 0x4e9004e9;
    // generous bound on the AES-encrypted packet body (IV + ciphertext + padding); real packets
    // are far smaller (largest is audio data capped at 2048 bytes plus a few header fields)
    private static final int MAX_ENCRYPTED_BODY_SIZE = 4096;
    private static final PacketRegistry PACKETS = new PacketRegistry();

    static {
        int lastPacketId = 0x0;

        PACKETS.register(++lastPacketId, PacketDirection.ANY, PingPacket.class, PingPacket::new);
        PACKETS.register(++lastPacketId, PacketDirection.SERVER, PlayerAudioPacket.class, PlayerAudioPacket::new);
        PACKETS.register(++lastPacketId, PacketDirection.CLIENT, SourceAudioPacket.class, SourceAudioPacket::new);
        PACKETS.register(++lastPacketId, PacketDirection.CLIENT, SelfAudioInfoPacket.class, SelfAudioInfoPacket::new);
        PACKETS.register(++lastPacketId, PacketDirection.CLIENT, SourceEndPacket.class, SourceEndPacket::new);
        PACKETS.register(0x100, PacketDirection.ANY, CustomPacket.class, CustomPacket::new);
    }

    public static byte[] encode(Packet<?> packet, UUID secret, @NotNull Encryption encryption) {
        int type = PACKETS.getType(packet);
        if (type < 0) return null;

        ByteArrayDataOutput body = ByteStreams.newDataOutput();
        try {
            packet.write(body);
        } catch (IOException e) {
            LOGGER.error(
                "Failed to serialize {}",
                packet.getClass()
                    .getSimpleName(),
                e);
            return null;
        }

        byte[] encryptedBody;
        try {
            encryptedBody = encryption.encrypt(body.toByteArray());
        } catch (EncryptionException e) {
            LOGGER.error(
                "Failed to encrypt {}",
                packet.getClass()
                    .getSimpleName(),
                e);
            return null;
        }

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeInt(MAGIC_NUMBER);
        out.writeByte(type);
        PacketUtil.writeUUID(out, secret);
        out.writeLong(System.currentTimeMillis());
        PacketUtil.writeBytes(out, encryptedBody);

        return out.toByteArray();
    }

    public static Optional<PacketUdp> decode(ByteArrayDataInput in) throws IOException {
        return decode(in, PacketDirection.ANY);
    }

    /**
     * Reads the packet header and the still-encrypted body. The body is deliberately not decrypted
     * here: the key is per-session and only the caller (after resolving {@code secret} to a
     * session) knows which one to use - see {@link PacketUdp#getPacketUntyped}.
     */
    public static Optional<PacketUdp> decode(@NotNull ByteArrayDataInput in, @NotNull PacketDirection direction)
        throws IOException {
        try {
            if (in.readInt() != MAGIC_NUMBER) return Optional.empty(); // bad packet
        } catch (Exception e) {
            return Optional.empty();
        }

        Packet<?> packet = PACKETS.byType(in.readByte(), direction);
        if (packet == null) return Optional.empty();

        UUID secret = PacketUtil.readUUID(in);
        long timestamp = in.readLong();
        byte[] encryptedBody = PacketUtil.readBytes(in, MAX_ENCRYPTED_BODY_SIZE);

        return Optional.of(new PacketUdp(secret, timestamp, packet, encryptedBody));
    }

    private PacketUdpCodec() {}
}
