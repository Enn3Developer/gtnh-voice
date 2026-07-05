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

    /**
     * sourceState bit 0: set for flat playback (full gain, no spatialization), clear for positional/proximity
     * (the speaker's world position applies, gain attenuates with distance). Positional is deliberately the
     * zero/legacy wire value: peers that predate this flag always sent 0, so a version-skewed pairing degrades
     * to the proximity behavior it always had instead of silently flattening all audio. Decided per packet by
     * the server-side group routing the frame, so a speaker switching groups mid-stream flips modes seamlessly.
     */
    public static final byte FLAG_FLAT = 0b0000_0001;

    /** sourceState for plain positional playback: no flags set - the legacy wire value. */
    public static final byte STATE_POSITIONAL = 0;

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

    /** Whether this frame plays positionally - true unless {@link #FLAG_FLAT} is set. */
    public boolean isPositional() {
        return (sourceState & FLAG_FLAT) == 0;
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
