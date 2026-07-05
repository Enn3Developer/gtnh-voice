package com.enn3developer.gtnhvoice.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * S-&gt;C. The receiving player's own current voice group display name, rendered inside the [] of the
 * HUD self row. Sent when their session is established and again whenever the server reassigns them;
 * never broadcast - each player only ever learns their own group. {@code protocolVersion} is read
 * first, matching the other control packets.
 */
public class VoiceGroupUpdatePacket implements IMessage {

    private byte protocolVersion;
    private String groupDisplayName;

    public VoiceGroupUpdatePacket() {}

    public VoiceGroupUpdatePacket(byte protocolVersion, String groupDisplayName) {
        this.protocolVersion = protocolVersion;
        this.groupDisplayName = groupDisplayName;
    }

    public byte getProtocolVersion() {
        return protocolVersion;
    }

    public String getGroupDisplayName() {
        return groupDisplayName;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = buf.readByte();
        groupDisplayName = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(protocolVersion);
        ByteBufUtils.writeUTF8String(buf, groupDisplayName);
    }
}
