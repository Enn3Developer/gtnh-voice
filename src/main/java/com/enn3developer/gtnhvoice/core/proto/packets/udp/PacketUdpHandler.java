/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.packets.PacketHandler;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.CustomPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.PingPacket;

public interface PacketUdpHandler extends PacketHandler {

    void handle(@NotNull PingPacket packet);

    void handle(@NotNull CustomPacket packet);
}
