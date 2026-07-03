/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.packets.PacketUtil;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound.BaseAudioPacket;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;

public final class PlayerAudioPacket extends BaseAudioPacket<ServerPacketUdpHandler> {

    private UUID activationId;
    private short distance;
    private boolean stereo;

    public PlayerAudioPacket() {}

    public PlayerAudioPacket(long sequenceNumber, byte[] data, @NotNull UUID activationId, short distance,
        boolean stereo) {
        super(sequenceNumber, data);

        this.activationId = checkNotNull(activationId);
        this.distance = distance;
        this.stereo = stereo;
    }

    public UUID getActivationId() {
        return activationId;
    }

    public short getDistance() {
        return distance;
    }

    public boolean isStereo() {
        return stereo;
    }

    @Override
    public void read(ByteArrayDataInput in) throws IOException {
        super.read(in);

        this.activationId = PacketUtil.readUUID(in);
        this.distance = in.readShort();
        this.stereo = in.readBoolean();
    }

    @Override
    public void write(ByteArrayDataOutput out) throws IOException {
        super.write(out);

        PacketUtil.writeUUID(out, checkNotNull(activationId));
        out.writeShort(distance);
        out.writeBoolean(stereo);
    }

    @Override
    public void handle(ServerPacketUdpHandler handler) {
        handler.handle(this);
    }

    @Override
    public String toString() {
        return "PlayerAudioPacket(activationId=" + activationId
            + ", distance="
            + distance
            + ", stereo="
            + stereo
            + ", "
            + super.toString()
            + ")";
    }
}
