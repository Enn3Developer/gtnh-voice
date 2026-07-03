/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp;

import java.io.IOException;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketHandler;
import com.google.common.io.ByteArrayDataInput;

public class PacketUdp {

    private final UUID secret;
    private final long timestamp;
    private final Packet<?> packet;

    private ByteArrayDataInput input;
    private boolean read;

    public PacketUdp(@NotNull UUID secret, long timestamp, @NotNull Packet<?> packet,
        @NotNull ByteArrayDataInput input) {
        this.secret = secret;
        this.timestamp = timestamp;
        this.packet = packet;
        this.input = input;
    }

    public PacketUdp(@NotNull UUID secret, long timestamp, @NotNull Packet<?> packet) {
        this.secret = secret;
        this.timestamp = timestamp;
        this.packet = packet;
        this.read = true;
    }

    public UUID getSecret() {
        return secret;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public ByteArrayDataInput getInput() {
        return input;
    }

    public boolean isRead() {
        return read;
    }

    public Packet<?> getPacketUntyped() throws IOException {
        if (!read) readPacket();

        return packet;
    }

    @SuppressWarnings("unchecked")
    public <T extends PacketHandler> Packet<T> getPacket() throws IOException {
        if (!read) readPacket();

        return (Packet<T>) packet;
    }

    private synchronized void readPacket() throws IOException {
        if (input == null) return;

        this.read = true;
        packet.read(input);
        this.input = null;
    }

    @Override
    public String toString() {
        return "PacketUdp(secret=" + secret + ", timestamp=" + timestamp + ")";
    }
}
