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
    private double x;
    private double y;
    private double z;

    public SourceAudioPacket() {}

    public SourceAudioPacket(long sequenceNumber, byte sourceState, byte[] data, @NotNull UUID sourceId, double x,
        double y, double z) {
        super(sequenceNumber, data);
        this.sourceId = sourceId;
        this.sourceState = sourceState;
        this.x = x;
        this.y = y;
        this.z = z;
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

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    @Override
    public void read(ByteArrayDataInput in) throws IOException {
        super.read(in);

        this.sourceId = PacketUtil.readUUID(in);
        this.sourceState = in.readByte();
        this.x = in.readDouble();
        this.y = in.readDouble();
        this.z = in.readDouble();
    }

    @Override
    public void write(ByteArrayDataOutput out) throws IOException {
        super.write(out);

        PacketUtil.writeUUID(out, checkNotNull(sourceId, "sourceId"));
        out.writeByte(sourceState);
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
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
            + ", x="
            + x
            + ", y="
            + y
            + ", z="
            + z
            + ", "
            + super.toString()
            + ")";
    }
}
