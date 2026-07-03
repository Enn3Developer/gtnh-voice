package com.enn3developer.gtnhvoice.core.transport;

import java.net.InetSocketAddress;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdp;

/**
 * Callback for packets decoded by {@link UdpTransportClient} or {@link UdpTransportServer}.
 * The transport layer knows nothing about players, secrets registries, or MC - it just
 * reports decoded packets and where they came from.
 */
@FunctionalInterface
public interface UdpPacketListener {

    void onPacket(@NotNull PacketUdp packet, @NotNull InetSocketAddress sender);
}
