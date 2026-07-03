package com.enn3developer.gtnhvoice.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * S-&gt;C. Sent instead of {@link ServerHelloPacket} when the handshake can't proceed (currently only
 * a protocol version mismatch). The client must not open UDP on receiving this.
 */
public class ServerRejectPacket implements IMessage {

    private byte serverProtocolVersion;
    private byte reason;

    public ServerRejectPacket() {}

    public ServerRejectPacket(byte serverProtocolVersion, byte reason) {
        this.serverProtocolVersion = serverProtocolVersion;
        this.reason = reason;
    }

    public byte getServerProtocolVersion() {
        return serverProtocolVersion;
    }

    public byte getReason() {
        return reason;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        serverProtocolVersion = buf.readByte();
        reason = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(serverProtocolVersion);
        buf.writeByte(reason);
    }
}
