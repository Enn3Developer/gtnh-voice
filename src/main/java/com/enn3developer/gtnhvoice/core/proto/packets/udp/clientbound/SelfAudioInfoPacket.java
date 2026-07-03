/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketUtil;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;

public final class SelfAudioInfoPacket implements Packet<ClientPacketUdpHandler> {

    private UUID sourceId;
    private long sequenceNumber;
    private byte[] data;
    private short distance;

    public SelfAudioInfoPacket() {}

    public SelfAudioInfoPacket(UUID sourceId, long sequenceNumber, byte[] data, short distance) {
        this.sourceId = sourceId;
        this.sequenceNumber = sequenceNumber;
        this.data = data;
        this.distance = distance;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public short getDistance() {
        return distance;
    }

    /**
     * Returns data only if it has been changed on the server.
     */
    public Optional<byte[]> getData() {
        return Optional.ofNullable(data);
    }

    @Override
    public void read(ByteArrayDataInput in) throws IOException {
        this.sourceId = PacketUtil.readUUID(in);
        this.sequenceNumber = in.readLong();
        if (in.readBoolean()) {
            int length = PacketUtil.readSafeInt(in, 1, 2048);
            byte[] data = new byte[length];
            in.readFully(data);
            this.data = data;
        }
        this.distance = in.readShort();
    }

    @Override
    public void write(ByteArrayDataOutput out) throws IOException {
        PacketUtil.writeUUID(out, checkNotNull(sourceId, "sourceId"));
        out.writeLong(sequenceNumber);
        out.writeBoolean(data != null);
        if (data != null) {
            out.writeInt(data.length);
            out.write(data);
        }
        out.writeShort(distance);
    }

    @Override
    public void handle(ClientPacketUdpHandler handler) {
        handler.handle(this);
    }

    @Override
    public String toString() {
        return "SelfAudioInfoPacket(sourceId=" + sourceId
            + ", sequenceNumber="
            + sequenceNumber
            + ", distance="
            + distance
            + ")";
    }
}
