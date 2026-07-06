/*
 * Adapted from Plasmo Voice (su.plo.voice.socket.NettyPacketUdp), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.transport;

import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdp;

import io.netty.channel.socket.DatagramPacket;

public final class NettyPacketUdp {

    private final DatagramPacket datagramPacket;
    private final PacketUdp packetUdp;

    public NettyPacketUdp(DatagramPacket datagramPacket, PacketUdp packetUdp) {
        this.datagramPacket = datagramPacket;
        this.packetUdp = packetUdp;
    }

    public DatagramPacket getDatagramPacket() {
        return datagramPacket;
    }

    public PacketUdp getPacketUdp() {
        return packetUdp;
    }
}
