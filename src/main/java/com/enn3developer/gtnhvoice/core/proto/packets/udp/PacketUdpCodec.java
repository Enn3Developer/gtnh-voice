/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketDirection;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketRegistry;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketUtil;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.CustomPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.PingPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SelfAudioInfoPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

public class PacketUdpCodec {

    // magic number is used to filter packets received not from us
    private static final int MAGIC_NUMBER = 0x4e9004e9;
    private static final PacketRegistry PACKETS = new PacketRegistry();

    static {
        int lastPacketId = 0x0;

        PACKETS.register(++lastPacketId, PacketDirection.ANY, PingPacket.class, PingPacket::new);
        PACKETS.register(++lastPacketId, PacketDirection.SERVER, PlayerAudioPacket.class, PlayerAudioPacket::new);
        PACKETS.register(++lastPacketId, PacketDirection.CLIENT, SourceAudioPacket.class, SourceAudioPacket::new);
        PACKETS.register(++lastPacketId, PacketDirection.CLIENT, SelfAudioInfoPacket.class, SelfAudioInfoPacket::new);
        PACKETS.register(0x100, PacketDirection.ANY, CustomPacket.class, CustomPacket::new);
    }

    public static byte[] replaceSecret(byte[] data, UUID secret) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        PacketUtil.writeUUID(out, secret);

        System.arraycopy(out.toByteArray(), 0, data, 5, 16);
        return data;
    }

    public static byte[] encode(Packet<?> packet, UUID secret) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();

        int type = PACKETS.getType(packet);
        if (type < 0) return null;

        out.writeInt(MAGIC_NUMBER);
        out.writeByte(type);
        PacketUtil.writeUUID(out, secret);
        out.writeLong(System.currentTimeMillis());

        try {
            packet.write(out);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return out.toByteArray();
    }

    public static Optional<PacketUdp> decode(ByteArrayDataInput in) throws IOException {
        return decode(in, PacketDirection.ANY);
    }

    public static Optional<PacketUdp> decode(@NotNull ByteArrayDataInput in, @NotNull PacketDirection direction)
        throws IOException {
        try {
            if (in.readInt() != MAGIC_NUMBER) return Optional.empty(); // bad packet
        } catch (Exception e) {
            return Optional.empty();
        }

        Packet<?> packet = PACKETS.byType(in.readByte(), direction);
        if (packet != null) {
            UUID secret = PacketUtil.readUUID(in);
            long timestamp = in.readLong();

            return Optional.of(new PacketUdp(secret, timestamp, packet, in));
        }

        return Optional.empty();
    }

    private PacketUdpCodec() {}
}
