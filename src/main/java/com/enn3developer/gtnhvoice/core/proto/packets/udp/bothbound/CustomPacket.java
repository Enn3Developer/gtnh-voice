/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;

import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdpHandler;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;

public class CustomPacket implements Packet<PacketUdpHandler> {

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
