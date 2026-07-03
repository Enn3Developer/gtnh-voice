/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdpHandler;

public interface ServerPacketUdpHandler extends PacketUdpHandler {

    void handle(@NotNull PlayerAudioPacket packet);
}
