/*
 * Adapted from Plasmo Voice (su.plo.voice.socket.NettyPacketUdpDecoder), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.transport;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import com.enn3developer.gtnhvoice.core.proto.packets.PacketDirection;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdp;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdpCodec;
import com.google.common.io.ByteStreams;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;

public final class NettyPacketUdpDecoder extends MessageToMessageDecoder<DatagramPacket> {

    private final PacketDirection direction;

    public NettyPacketUdpDecoder(PacketDirection direction) {
        this.direction = direction;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket packet, List<Object> out) throws Exception {
        ByteBuf content = packet.content();
        byte[] bytes = new byte[content.readableBytes()];
        content.readBytes(bytes);

        Optional<PacketUdp> packetUdp = PacketUdpCodec.decode(ByteStreams.newDataInput(bytes), direction);
        if (!packetUdp.isPresent()) throw new IOException("Invalid packet header");

        out.add(new NettyPacketUdp(packet, packetUdp.get()));
    }
}
