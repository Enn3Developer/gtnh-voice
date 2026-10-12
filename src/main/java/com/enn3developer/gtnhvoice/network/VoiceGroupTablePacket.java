package com.enn3developer.gtnhvoice.network;

import java.util.LinkedHashMap;
import java.util.Map;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * S-&gt;C. The full wire-id to {@link GroupNames} table of every voice group, so clients can attribute incoming
 * audio frames (each {@code SourceAudioPacket} carries the routing group's id) by internal name - what playback
 * filters key on - and by display name and display component - what the HUD shows. Full-replace semantics -
 * idempotent and reorder-proof - sent when a session is established and rebroadcast to everyone whenever a group
 * registers late. {@code protocolVersion} is read first, matching the other control packets.
 * <p>
 * Wire format (protocol v7): {@code byte protocolVersion, short count, count x {short id, UTF-8 name, UTF-8
 * displayName, UTF-8 displayComponent}}, the display component being {@code IChatComponent.Serializer} JSON, or an
 * empty string for a group without one. Decoding is bounded the same way as the roster snapshot: a count the
 * remaining bytes could not possibly hold, a negative count or a repeated id is rejected with
 * {@link IllegalArgumentException} before anything is stored, and each string is length-capped by FML's two-byte
 * varint prefix. The JSON is parsed only by the client's group table, so one bad component can't lose the table.
 */
public class VoiceGroupTablePacket implements IMessage {

    /** One entry's floor on the wire: the short id plus three empty strings' one-byte length prefixes. */
    private static final int MIN_ENTRY_BYTES = 5;

    private byte protocolVersion;
    private Map<Short, GroupNames> groupTable = new LinkedHashMap<>();

    public VoiceGroupTablePacket() {}

    public VoiceGroupTablePacket(byte protocolVersion, Map<Short, GroupNames> groupTable) {
        this.protocolVersion = protocolVersion;
        this.groupTable = groupTable;
    }

    public byte getProtocolVersion() {
        return protocolVersion;
    }

    /** Wire id to the group's names, in the order written by the server (built-ins first). */
    public Map<Short, GroupNames> getGroupTable() {
        return groupTable;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = buf.readByte();
        int count = buf.readShort();
        if (count < 0 || count > buf.readableBytes() / MIN_ENTRY_BYTES)
            throw new IllegalArgumentException("Invalid group table count: " + count);
        Map<Short, GroupNames> table = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            short id = buf.readShort();
            String name = ByteBufUtils.readUTF8String(buf);
            String displayName = ByteBufUtils.readUTF8String(buf);
            String displayComponentJson = ByteBufUtils.readUTF8String(buf);
            GroupNames names = new GroupNames(
                name,
                displayName,
                displayComponentJson.isEmpty() ? null : displayComponentJson);
            if (table.putIfAbsent(id, names) != null)
                throw new IllegalArgumentException("Duplicate group id in table: " + id);
        }
        groupTable = table;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(protocolVersion);
        buf.writeShort(groupTable.size());
        for (Map.Entry<Short, GroupNames> entry : groupTable.entrySet()) {
            buf.writeShort(entry.getKey());
            ByteBufUtils.writeUTF8String(
                buf,
                entry.getValue()
                    .name());
            ByteBufUtils.writeUTF8String(
                buf,
                entry.getValue()
                    .displayName());
            String displayComponentJson = entry.getValue()
                .displayComponentJson();
            ByteBufUtils.writeUTF8String(buf, displayComponentJson == null ? "" : displayComponentJson);
        }
    }
}
