package com.enn3developer.gtnhvoice.network;

import java.util.UUID;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * S-&gt;C. Sent after a compatible {@link ClientHelloPacket}. Carries the server's ephemeral 32-byte
 * raw X25519 public key ({@code publicKey}) for the client to complete the ECDH, the per-login
 * {@code sessionId} (a public token used only as the UDP header + server session lookup, never for
 * key derivation), and the server-authoritative voice config the client should open its UDP link
 * with. {@code protocolVersion} is read first, matching {@link ClientHelloPacket}.
 */
public class ServerHelloPacket implements IMessage {

    private byte protocolVersion;
    private UUID sessionId;
    private byte[] publicKey;
    private String udpHost;
    private int udpPort;
    private int distance;
    private byte opusMode;
    private int frameSize;
    private int sampleRate;
    private int capabilityFlags;

    public ServerHelloPacket() {}

    public ServerHelloPacket(byte protocolVersion, UUID sessionId, byte[] publicKey, String udpHost, int udpPort,
        int distance, byte opusMode, int frameSize, int sampleRate, int capabilityFlags) {
        // fromBytes reads exactly X25519_PUBLIC_KEY_LENGTH bytes, so a wrong-length key here would
        // desync the receiver's buffer and corrupt the following fields - fail fast at build time.
        if (publicKey == null || publicKey.length != VoiceProtocol.X25519_PUBLIC_KEY_LENGTH) {
            throw new IllegalArgumentException(
                "ServerHello public key must be " + VoiceProtocol.X25519_PUBLIC_KEY_LENGTH + " bytes");
        }
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

    public byte getProtocolVersion() {
        return protocolVersion;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    /** The server's ephemeral raw X25519 public key (32 bytes), or {@code null} if none was sent. */
    public byte[] getPublicKey() {
        return publicKey;
    }

    public String getUdpHost() {
        return udpHost;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public int getDistance() {
        return distance;
    }

    public byte getOpusMode() {
        return opusMode;
    }

    public int getFrameSize() {
        return frameSize;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getCapabilityFlags() {
        return capabilityFlags;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = buf.readByte();
        sessionId = new UUID(buf.readLong(), buf.readLong());
        publicKey = new byte[VoiceProtocol.X25519_PUBLIC_KEY_LENGTH];
        buf.readBytes(publicKey);
        udpHost = ByteBufUtils.readUTF8String(buf);
        udpPort = buf.readInt();
        distance = buf.readInt();
        opusMode = buf.readByte();
        frameSize = buf.readInt();
        sampleRate = buf.readInt();
        capabilityFlags = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(protocolVersion);
        buf.writeLong(sessionId.getMostSignificantBits());
        buf.writeLong(sessionId.getLeastSignificantBits());
        buf.writeBytes(publicKey);
        ByteBufUtils.writeUTF8String(buf, udpHost);
        buf.writeInt(udpPort);
        buf.writeInt(distance);
        buf.writeByte(opusMode);
        buf.writeInt(frameSize);
        buf.writeInt(sampleRate);
        buf.writeInt(capabilityFlags);
    }
}
