/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.data.encryption;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;

import com.enn3developer.gtnhvoice.core.proto.packets.PacketSerializable;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketUtil;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;

public final class EncryptionInfo implements PacketSerializable {

    private String algorithm;
    private byte[] data;

    public EncryptionInfo() {}

    public EncryptionInfo(String algorithm, byte[] data) {
        this.algorithm = algorithm;
        this.data = data;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public byte[] getData() {
        return data;
    }

    @Override
    public void deserialize(ByteArrayDataInput in) throws IOException {
        this.algorithm = in.readUTF();

        int length = PacketUtil.readSafeInt(in, 1, 2048);
        byte[] data = new byte[length];
        in.readFully(data);
        this.data = data;
    }

    @Override
    public void serialize(ByteArrayDataOutput out) {
        checkNotNull(algorithm, "algorithm cannot be null");
        checkNotNull(data, "data cannot be null");

        out.writeUTF(algorithm);

        out.writeInt(data.length);
        out.write(data);
    }

    @Override
    public String toString() {
        return "EncryptionInfo(algorithm=" + algorithm + ", data=" + java.util.Arrays.toString(data) + ")";
    }
}
