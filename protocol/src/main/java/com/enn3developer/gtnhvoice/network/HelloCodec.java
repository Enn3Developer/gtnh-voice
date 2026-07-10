package com.enn3developer.gtnhvoice.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The single source of truth for the ClientHello/ServerHello <em>body</em> wire format - the bytes that travel inside
 * the {@code gtnhvoice} SimpleNetworkWrapper control channel after the SimpleNetworkWrapper discriminator byte.
 * <p>
 * This is deliberately netty-free (plain {@code byte[]}/{@link DataInput}/{@link DataOutput}) so it can live in the
 * shared {@code :protocol} module and be consumed both by the mod (whose {@code ClientHelloPacket}/
 * {@code ServerHelloPacket} implement FML's {@code IMessage} but delegate their body encode/decode here) and by the
 * out-of-tree test harness. A protocol change made here therefore breaks the harness at compile time instead of
 * silently desyncing it at runtime.
 * <p>
 * <b>String framing.</b> {@link #writeUtf8String}/{@link #readUtf8String} reproduce FML's
 * {@code ByteBufUtils.writeUTF8String}/{@code readUTF8String} byte-for-byte: an unsigned LEB128 VarInt byte-length
 * prefix followed by the UTF-8 bytes (an empty string is a single {@code 0x00}). This is the ordinary LEB128 VarInt,
 * <em>not</em> the "varShort" the vanilla S3F custom-payload frame length uses - the two were confused once and it
 * silently desynced the harness, which is the whole reason this shared codec exists.
 */
public final class HelloCodec {

    private HelloCodec() {}

    /**
     * Upper bound on the decoded byte-length of any UTF-8 string in a hello body. The real strings are
     * tiny (a mod version string, an empty {@code udpHost}), so an 8 KiB cap is generous while making the
     * unbounded {@code new byte[length]} allocation blowup (a 6-byte hello claiming ~2 GiB) impossible:
     * {@link #readUtf8String} rejects the length prefix before allocating. Mirrors the {@code max} bound
     * {@link com.enn3developer.gtnhvoice.core.proto.packets.PacketUtil#readBytes} already applies on the
     * UDP path.
     */
    public static final int MAX_UTF8_STRING_LENGTH = 8 * 1024;

    // ---------------------------------------------------------------------------------------------
    // ClientHello (C->S) body
    // ---------------------------------------------------------------------------------------------

    /** The decoded ClientHello body fields (SimpleNetworkWrapper discriminator already stripped). */
    public static final class ClientHello {
        public final byte protocolVersion;
        public final String modVersion;
        /** The client's ephemeral raw X25519 public key (32 bytes), or {@code null} if none was present. */
        public final byte[] publicKey;

        public ClientHello(byte protocolVersion, String modVersion, byte[] publicKey) {
            this.protocolVersion = protocolVersion;
            this.modVersion = modVersion;
            this.publicKey = publicKey;
        }
    }

    public static byte[] encodeClientHello(byte protocolVersion, String modVersion, byte[] publicKey) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        try {
            out.writeByte(protocolVersion);
            writeUtf8String(out, modVersion);
            // Written only when present, mirroring the mod's tolerant ClientHello: a version-mismatched
            // peer may legitimately send a differently-shaped body.
            if (publicKey != null) out.write(publicKey);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    public static ClientHello decodeClientHello(byte[] body) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        try {
            byte protocolVersion = in.readByte();
            String modVersion = readUtf8String(in);
            // Read the public key tolerantly: a version-mismatched peer may send a differently-shaped body,
            // and the caller still wants to run its handshake logic and issue a clean version-mismatch reject
            // rather than throwing here. A correct v4 client always includes the full 32 bytes.
            byte[] publicKey = null;
            if (in.available() >= VoiceProtocol.X25519_PUBLIC_KEY_LENGTH) {
                publicKey = new byte[VoiceProtocol.X25519_PUBLIC_KEY_LENGTH];
                in.readFully(publicKey);
            }
            return new ClientHello(protocolVersion, modVersion, publicKey);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ServerHello (S->C) body
    // ---------------------------------------------------------------------------------------------

    /** The decoded ServerHello body fields (SimpleNetworkWrapper discriminator already stripped). */
    public static final class ServerHello {
        public final byte protocolVersion;
        public final UUID sessionId;
        /** The server's ephemeral raw X25519 public key (32 bytes). */
        public final byte[] publicKey;
        public final String udpHost;
        public final int udpPort;
        public final int distance;
        public final byte opusMode;
        public final int frameSize;
        public final int sampleRate;
        public final int capabilityFlags;

        public ServerHello(byte protocolVersion, UUID sessionId, byte[] publicKey, String udpHost, int udpPort,
            int distance, byte opusMode, int frameSize, int sampleRate, int capabilityFlags) {
            this.protocolVersion = protocolVersion;
            this.sessionId = sessionId;
            this.publicKey = publicKey;
            this.udpHost = udpHost;
            this.udpPort = udpPort;
            this.distance = distance;
            this.opusMode = opusMode;
            this.frameSize = frameSize;
            this.sampleRate = sampleRate;
            this.capabilityFlags = capabilityFlags;
        }
    }

    public static byte[] encodeServerHello(byte protocolVersion, UUID sessionId, byte[] publicKey, String udpHost,
        int udpPort, int distance, byte opusMode, int frameSize, int sampleRate, int capabilityFlags) {
        // fromBytes/decode reads exactly X25519_PUBLIC_KEY_LENGTH bytes, so a wrong-length key here would
        // desync the receiver's cursor and corrupt the following fields - fail fast at build time.
        if (publicKey == null || publicKey.length != VoiceProtocol.X25519_PUBLIC_KEY_LENGTH) {
            throw new IllegalArgumentException(
                "ServerHello public key must be " + VoiceProtocol.X25519_PUBLIC_KEY_LENGTH + " bytes");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        try {
            out.writeByte(protocolVersion);
            out.writeLong(sessionId.getMostSignificantBits());
            out.writeLong(sessionId.getLeastSignificantBits());
            out.write(publicKey);
            writeUtf8String(out, udpHost);
            out.writeInt(udpPort);
            out.writeInt(distance);
            out.writeByte(opusMode);
            out.writeInt(frameSize);
            out.writeInt(sampleRate);
            out.writeInt(capabilityFlags);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    public static ServerHello decodeServerHello(byte[] body) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        try {
            byte protocolVersion = in.readByte();
            UUID sessionId = new UUID(in.readLong(), in.readLong());
            byte[] publicKey = new byte[VoiceProtocol.X25519_PUBLIC_KEY_LENGTH];
            in.readFully(publicKey);
            String udpHost = readUtf8String(in);
            int udpPort = in.readInt();
            int distance = in.readInt();
            byte opusMode = in.readByte();
            int frameSize = in.readInt();
            int sampleRate = in.readInt();
            int capabilityFlags = in.readInt();
            return new ServerHello(protocolVersion, sessionId, publicKey, udpHost, udpPort, distance, opusMode,
                frameSize, sampleRate, capabilityFlags);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ByteBufUtils-compatible LEB128 VarInt + UTF-8 string framing
    // ---------------------------------------------------------------------------------------------

    /** Reproduces {@code ByteBufUtils.writeUTF8String}: LEB128 VarInt byte-length prefix + UTF-8 bytes. */
    public static void writeUtf8String(DataOutput out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    /** Reproduces {@code ByteBufUtils.readUTF8String}: LEB128 VarInt byte-length prefix + UTF-8 bytes. */
    public static String readUtf8String(DataInput in) throws IOException {
        int length = readVarInt(in);
        // Validate the remote-controlled length prefix BEFORE allocating: readVarInt permits ~2 GiB, so
        // a 6-byte hello would otherwise force a giant heap allocation on the server thread (CWE-789), long
        // before the per-player HelloRateLimiter ever sees the packet.
        if (length < 0 || length > MAX_UTF8_STRING_LENGTH) {
            throw new IOException("UTF-8 string length out of range (max: " + MAX_UTF8_STRING_LENGTH
                + ", value: " + length + ")");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Unsigned LEB128 VarInt, matching FML's {@code ByteBufUtils.writeVarInt} and the vanilla protocol VarInt. */
    public static void writeVarInt(DataOutput out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    /** Unsigned LEB128 VarInt, matching FML's {@code ByteBufUtils.readVarInt} and the vanilla protocol VarInt. */
    public static int readVarInt(DataInput in) throws IOException {
        int value = 0;
        int position = 0;
        int b;
        do {
            b = in.readUnsignedByte();
            value |= (b & 0x7F) << position;
            position += 7;
            if (position > 35) throw new IOException("VarInt too big");
        } while ((b & 0x80) != 0);
        return value;
    }
}
