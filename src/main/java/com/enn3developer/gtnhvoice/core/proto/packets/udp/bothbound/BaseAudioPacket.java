/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.IOException;

import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketHandler;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketUtil;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;

public abstract class BaseAudioPacket<T extends PacketHandler> implements Packet<T> {

    protected long sequenceNumber;
    protected byte[] data;

    protected BaseAudioPacket() {}

    public BaseAudioPacket(long sequenceNumber, byte[] data) {
        this.sequenceNumber = sequenceNumber;
        this.data = data;

        checkArgument(data.length > 0, "audio data cannot be empty");
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public byte[] getData() {
        return data;
    }

    @Override
    public void read(ByteArrayDataInput in) throws IOException {
        this.sequenceNumber = in.readLong();

        int length = PacketUtil.readSafeInt(in, 1, 2048);
        byte[] data = new byte[length];
        in.readFully(data);
        this.data = data;
    }

    @Override
    public void write(ByteArrayDataOutput out) throws IOException {
        out.writeLong(sequenceNumber);

        out.writeInt(data.length);
        out.write(data);
    }

    @Override
    public String toString() {
        return getClass()
            .getSimpleName() + "(sequenceNumber=" + sequenceNumber + ", data=" + java.util.Arrays.toString(data) + ")";
    }
}
