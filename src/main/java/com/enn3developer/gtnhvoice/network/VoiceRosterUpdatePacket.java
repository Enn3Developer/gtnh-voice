package com.enn3developer.gtnhvoice.network;

import java.util.UUID;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * S-&gt;C. Incremental voice roster change: one player joined or left voice. Sent to every other
 * in-voice player when a session is established ({@link #MODE_ADD}) or torn down ({@link
 * #MODE_REMOVE}), mirroring {@link VoiceRosterSnapshotPacket}'s entry shape. {@code
 * protocolVersion} is read first, matching the other control packets.
 */
public class VoiceRosterUpdatePacket implements IMessage {

    public static final byte MODE_ADD = 0;
    public static final byte MODE_REMOVE = 1;

    private byte protocolVersion;
    private byte mode;
    private UUID playerUuid;
    private String playerName;

    public VoiceRosterUpdatePacket() {}

    public VoiceRosterUpdatePacket(byte protocolVersion, byte mode, UUID playerUuid, String playerName) {
        this.protocolVersion = protocolVersion;
        this.mode = mode;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
    }

    public byte getProtocolVersion() {
        return protocolVersion;
    }

    public byte getMode() {
        return mode;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = buf.readByte();
        mode = buf.readByte();
        playerUuid = new UUID(buf.readLong(), buf.readLong());
        playerName = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(protocolVersion);
        buf.writeByte(mode);
        buf.writeLong(playerUuid.getMostSignificantBits());
        buf.writeLong(playerUuid.getLeastSignificantBits());
        ByteBufUtils.writeUTF8String(buf, playerName);
    }
}
