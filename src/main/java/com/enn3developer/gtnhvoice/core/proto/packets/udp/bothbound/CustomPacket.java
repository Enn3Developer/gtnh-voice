/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;

import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketUtil;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdpHandler;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;

public class CustomPacket implements Packet<PacketUdpHandler> {

    // Mirrors PacketUdpCodec's bound on the whole encrypted body - a custom payload can never legitimately
    // exceed what the datagram itself is allowed to carry.
    private static final int MAX_PAYLOAD_SIZE = 4096;

    private String addonId;
    private byte[] payload;

    public CustomPacket() {}

    public CustomPacket(String addonId, byte[] payload) {
        this.addonId = addonId;
        this.payload = payload;
    }

    public String getAddonId() {
        return addonId;
    }

    public byte[] getPayload() {
        return payload;
    }

    @Override
    public void read(ByteArrayDataInput in) throws IOException {
        this.addonId = in.readUTF();
        this.payload = PacketUtil.readBytes(in, MAX_PAYLOAD_SIZE);
    }

    @Override
    public void write(ByteArrayDataOutput out) throws IOException {
        checkNotNull(addonId, "addonId cannot be null");
        checkNotNull(payload, "payload cannot be null");

        out.writeUTF(addonId);
        out.writeInt(payload.length);
        out.write(payload);
    }

    @Override
    public void handle(PacketUdpHandler handler) {
        handler.handle(this);
    }

    @Override
    public String toString() {
        return "CustomPacket(addonId=" + addonId + ", payload=" + java.util.Arrays.toString(payload) + ")";
    }
}
