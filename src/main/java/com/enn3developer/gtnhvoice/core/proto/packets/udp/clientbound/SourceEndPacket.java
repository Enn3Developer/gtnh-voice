/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketUtil;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;

/**
 * S-&gt;C. Tells clients that {@code sourceId} (a player's UUID) is gone, so any {@code VoiceSource}
 * built from its audio should be torn down. Emitted by the server on player logout.
 */
public final class SourceEndPacket implements Packet<ClientPacketUdpHandler> {

    private UUID sourceId;

    public SourceEndPacket() {}

    public SourceEndPacket(@NotNull UUID sourceId) {
        this.sourceId = checkNotNull(sourceId);
    }

    public UUID getSourceId() {
        return sourceId;
    }

    @Override
    public void read(ByteArrayDataInput in) throws IOException {
        this.sourceId = PacketUtil.readUUID(in);
    }

    @Override
    public void write(ByteArrayDataOutput out) throws IOException {
        PacketUtil.writeUUID(out, checkNotNull(sourceId, "sourceId"));
    }

    @Override
    public void handle(ClientPacketUdpHandler handler) {
        handler.handle(this);
    }

    @Override
    public String toString() {
        return "SourceEndPacket(sourceId=" + sourceId + ")";
    }
}
