package com.enn3developer.gtnhvoice.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * S-&gt;C. Sent once, right after a player's voice session is established, carrying the full
 * current voice roster (every other in-voice player's UUID+name). The receiving player is never
 * included in their own snapshot. {@code protocolVersion} is read first, matching the other
 * control packets.
 */
public class VoiceRosterSnapshotPacket implements IMessage {

    private byte protocolVersion;
    private Map<UUID, String> roster;

    public VoiceRosterSnapshotPacket() {}

    public VoiceRosterSnapshotPacket(byte protocolVersion, Map<UUID, String> roster) {
        this.protocolVersion = protocolVersion;
        this.roster = roster;
    }

    public byte getProtocolVersion() {
        return protocolVersion;
    }

    public Map<UUID, String> getRoster() {
        return roster;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = buf.readByte();
        int count = buf.readInt();
        Map<UUID, String> map = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            UUID uuid = new UUID(buf.readLong(), buf.readLong());
            String name = ByteBufUtils.readUTF8String(buf);
            map.put(uuid, name);
        }
        roster = map;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(protocolVersion);
        buf.writeInt(roster.size());
        for (Map.Entry<UUID, String> entry : roster.entrySet()) {
            UUID uuid = entry.getKey();
            buf.writeLong(uuid.getMostSignificantBits());
            buf.writeLong(uuid.getLeastSignificantBits());
            ByteBufUtils.writeUTF8String(buf, entry.getValue());
        }
    }
}
