/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets.udp.bothbound;

import java.io.IOException;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdpHandler;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;

public class PingPacket implements Packet<PacketUdpHandler> {

    private long time = System.currentTimeMillis();

    private String serverIp;
    private int serverPort;

    public PingPacket() {}

    public PingPacket(@NotNull String serverIp, int serverPort) {
        this.serverIp = serverIp;
        this.serverPort = serverPort;
    }

    public long getTime() {
        return time;
    }

    public String getServerIp() {
        return serverIp;
    }

    public int getServerPort() {
        return serverPort;
    }

    @Override
    public void read(ByteArrayDataInput in) throws IOException {
        this.time = in.readLong();

        try {
            this.serverIp = in.readUTF();
            this.serverPort = in.readUnsignedShort();
        } catch (Exception ignored) {
            // ignore exceptions here, because it's optional
        }
    }

    @Override
    public void write(ByteArrayDataOutput out) throws IOException {
        out.writeLong(time);

        if (serverIp != null && serverPort > 0) {
            out.writeUTF(serverIp);
            out.writeShort(serverPort);
        }
    }

    @Override
    public void handle(PacketUdpHandler handler) {
        handler.handle(this);
    }

    @Override
    public String toString() {
        return "PingPacket(time=" + time + ", serverIp=" + serverIp + ", serverPort=" + serverPort + ")";
    }
}
