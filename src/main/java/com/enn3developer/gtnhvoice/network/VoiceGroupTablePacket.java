package com.enn3developer.gtnhvoice.network;

import java.util.LinkedHashMap;
import java.util.Map;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * S-&gt;C. The full wire-id to display-name table of every voice group, so clients can attribute incoming audio
 * frames (each {@code SourceAudioPacket} carries the routing group's id). Full-replace semantics - idempotent
 * and reorder-proof - sent when a session is established and rebroadcast to everyone whenever a group registers
 * late. {@code protocolVersion} is read first, matching the other control packets.
 */
public class VoiceGroupTablePacket implements IMessage {

    private byte protocolVersion;
    private Map<Short, String> groupTable = new LinkedHashMap<>();

    public VoiceGroupTablePacket() {}

    public VoiceGroupTablePacket(byte protocolVersion, Map<Short, String> groupTable) {
        this.protocolVersion = protocolVersion;
        this.groupTable = groupTable;
    }

    public byte getProtocolVersion() {
        return protocolVersion;
    }

    /** Wire id to display name, in the order written by the server (built-ins first). */
    public Map<Short, String> getGroupTable() {
        return groupTable;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = buf.readByte();
        int count = buf.readShort();
        groupTable = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            short id = buf.readShort();
            groupTable.put(id, ByteBufUtils.readUTF8String(buf));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(protocolVersion);
        buf.writeShort(groupTable.size());
        for (Map.Entry<Short, String> entry : groupTable.entrySet()) {
            buf.writeShort(entry.getKey());
            ByteBufUtils.writeUTF8String(buf, entry.getValue());
        }
    }
}
