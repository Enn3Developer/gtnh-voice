package com.enn3developer.gtnhvoice.network;

import java.util.UUID;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * S-&gt;C. Sent after a compatible {@link ClientHelloPacket}. Carries the fresh per-login session
 * secret and the server-authoritative voice config the client should open its UDP link with.
 * {@code protocolVersion} is read first, matching {@link ClientHelloPacket}.
 */
public class ServerHelloPacket implements IMessage {

    private byte protocolVersion;
    private UUID secret;
    private String udpHost;
    private int udpPort;
    private int distance;
    private byte opusMode;
    private int frameSize;
    private int sampleRate;
    private int capabilityFlags;

    public ServerHelloPacket() {}

    public ServerHelloPacket(byte protocolVersion, UUID secret, String udpHost, int udpPort, int distance,
        byte opusMode, int frameSize, int sampleRate, int capabilityFlags) {
        this.protocolVersion = protocolVersion;
        this.secret = secret;
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

    public UUID getSecret() {
        return secret;
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
        secret = new UUID(buf.readLong(), buf.readLong());
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
        buf.writeLong(secret.getMostSignificantBits());
        buf.writeLong(secret.getLeastSignificantBits());
        ByteBufUtils.writeUTF8String(buf, udpHost);
        buf.writeInt(udpPort);
        buf.writeInt(distance);
        buf.writeByte(opusMode);
        buf.writeInt(frameSize);
        buf.writeInt(sampleRate);
        buf.writeInt(capabilityFlags);
    }
}
