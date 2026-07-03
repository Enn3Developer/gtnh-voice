/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.packets.PacketUtil;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.BaseAudioPacket;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;

public final class SourceAudioPacket extends BaseAudioPacket<ClientPacketUdpHandler> {

    private UUID sourceId;
    private byte sourceState;
    private short distance;

    public SourceAudioPacket() {}

    public SourceAudioPacket(long sequenceNumber, byte sourceState, byte[] data, @NotNull UUID sourceId,
        short distance) {
        super(sequenceNumber, data);
        this.sourceId = sourceId;
        this.sourceState = sourceState;
        this.distance = distance;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public byte getSourceState() {
        return sourceState;
    }

    public void setSourceState(byte sourceState) {
        this.sourceState = sourceState;
    }

    public short getDistance() {
        return distance;
    }

    public void setDistance(short distance) {
        this.distance = distance;
    }

    @Override
    public void read(ByteArrayDataInput in) throws IOException {
        super.read(in);

        this.sourceId = PacketUtil.readUUID(in);
        this.sourceState = in.readByte();
        this.distance = in.readShort();
    }

    @Override
    public void write(ByteArrayDataOutput out) throws IOException {
        super.write(out);

        PacketUtil.writeUUID(out, checkNotNull(sourceId, "sourceId"));
        out.writeByte(sourceState);
        out.writeShort(distance);
    }

    @Override
    public void handle(ClientPacketUdpHandler handler) {
        handler.handle(this);
    }

    @Override
    public String toString() {
        return "SourceAudioPacket(sourceId=" + sourceId
            + ", sourceState="
            + sourceState
            + ", distance="
            + distance
            + ", "
            + super.toString()
            + ")";
    }
}
