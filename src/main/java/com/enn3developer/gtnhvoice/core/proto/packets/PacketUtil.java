/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets;

import java.io.IOException;
import java.util.UUID;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

public class PacketUtil {

    public static int readSafeInt(ByteArrayDataInput in, int minInt, int maxInt) throws IOException {
        int value = in.readInt();
        if (value < minInt || value > maxInt) {
            throw new IOException("Invalid int value (min: " + minInt + ", max: " + maxInt + ", value: " + value + ")");
        }
        return value;
    }

    public static void writeBytes(ByteArrayDataOutput out, byte[] bytes) {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static byte[] readBytes(ByteArrayDataInput in, int max) throws IOException {
        byte[] bytes = new byte[readSafeInt(in, 0, max)];
        in.readFully(bytes);
        return bytes;
    }

    public static void writeUUID(ByteArrayDataOutput out, UUID uuid) {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    public static UUID readUUID(ByteArrayDataInput in) {
        return new UUID(in.readLong(), in.readLong());
    }

    public static byte[] getUUIDBytes(UUID uuid) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        writeUUID(out, uuid);
        return out.toByteArray();
    }

    private PacketUtil() {}
}
