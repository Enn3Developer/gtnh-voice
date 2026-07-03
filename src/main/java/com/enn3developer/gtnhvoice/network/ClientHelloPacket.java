package com.enn3developer.gtnhvoice.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * C-&gt;S. Sent once the client player has joined a world and wants to negotiate a voice session.
 * {@code protocolVersion} is read first, before anything else, so a mismatching client is rejected
 * on protocol grounds rather than tripping over a misparsed body.
 */
public class ClientHelloPacket implements IMessage {

    private byte protocolVersion;
    private String modVersion;

    public ClientHelloPacket() {}

    public ClientHelloPacket(byte protocolVersion, String modVersion) {
        this.protocolVersion = protocolVersion;
        this.modVersion = modVersion;
    }

    public byte getProtocolVersion() {
        return protocolVersion;
    }

    public String getModVersion() {
        return modVersion;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = buf.readByte();
        modVersion = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(protocolVersion);
        ByteBufUtils.writeUTF8String(buf, modVersion);
    }
}
