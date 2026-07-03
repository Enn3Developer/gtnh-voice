/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdpHandler;

public interface ClientPacketUdpHandler extends PacketUdpHandler {

    void handle(@NotNull SourceAudioPacket packet);

    void handle(@NotNull SelfAudioInfoPacket packet);
}
