package com.enn3developer.gtnhvoice.network;

import org.jetbrains.annotations.Nullable;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * S-&gt;C. The receiving player's own current voice group display name, rendered inside the [] of the
 * HUD self row. Sent when their session is established and again whenever the server reassigns them;
 * never broadcast - each player only ever learns their own group. {@code protocolVersion} is read
 * first, matching the other control packets.
 * <p>
 * Wire format (protocol v7): {@code byte protocolVersion, UTF-8 groupDisplayName, UTF-8 groupDisplayComponent}, the
 * display component being the group's {@code IChatComponent.Serializer} JSON, which the client formats in its own
 * language and shows instead of the display name, or an empty string for a group without one.
 */
public class VoiceGroupUpdatePacket implements IMessage {

    private byte protocolVersion;
    private String groupDisplayName;
    private String groupDisplayComponentJson;

    public VoiceGroupUpdatePacket() {}

    /** An update naming a group without a display component. */
    public VoiceGroupUpdatePacket(byte protocolVersion, String groupDisplayName) {
        this(protocolVersion, groupDisplayName, null);
    }

    public VoiceGroupUpdatePacket(byte protocolVersion, String groupDisplayName,
        @Nullable String groupDisplayComponentJson) {
        this.protocolVersion = protocolVersion;
        this.groupDisplayName = groupDisplayName;
        this.groupDisplayComponentJson = groupDisplayComponentJson;
    }

    public byte getProtocolVersion() {
        return protocolVersion;
    }

    public String getGroupDisplayName() {
        return groupDisplayName;
    }

    /** The group's display component as JSON, or {@code null} when it has none. */
    public @Nullable String getGroupDisplayComponentJson() {
        return groupDisplayComponentJson;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = buf.readByte();
        groupDisplayName = ByteBufUtils.readUTF8String(buf);
        String json = ByteBufUtils.readUTF8String(buf);
        groupDisplayComponentJson = json.isEmpty() ? null : json;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(protocolVersion);
        ByteBufUtils.writeUTF8String(buf, groupDisplayName);
        ByteBufUtils.writeUTF8String(buf, groupDisplayComponentJson == null ? "" : groupDisplayComponentJson);
    }
}
