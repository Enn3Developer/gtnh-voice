package com.enn3developer.gtnhvoice.server.group;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

/**
 * Everything a group may touch while routing one audio frame, and nothing more: the immutable per-tick position
 * snapshot, a read-only view of the live sessions, and {@link #sendTo} as the only way to emit packets - groups
 * never see the UDP transport or the server manager directly, only the {@link PacketSender} seam. Built by the
 * server manager per routed frame, capturing the snapshot reference current at that moment, and handed to
 * {@link IGroup#route} on the UDP/Netty thread.
 */
public final class RoutingContext {

    private final PacketSender transport;
    private final Map<UUID, PlayerSnapshot> positionSnapshot;
    private final Map<UUID, VoiceServerSession> sessionsByPlayerUuid;

    public RoutingContext(@NotNull PacketSender transport, @NotNull Map<UUID, PlayerSnapshot> positionSnapshot,
        @NotNull Map<UUID, VoiceServerSession> sessionsByPlayerUuid) {
        this.transport = transport;
        this.positionSnapshot = positionSnapshot;
        this.sessionsByPlayerUuid = sessionsByPlayerUuid;
    }

    /** The position/dimension snapshot of every online player, as of the tick this frame arrived in. */
    public Map<UUID, PlayerSnapshot> getPositionSnapshot() {
        return positionSnapshot;
    }

    /** Read-only view of every live voice session, keyed by player UUID. */
    public Map<UUID, VoiceServerSession> getSessionsByPlayerUuid() {
        return sessionsByPlayerUuid;
    }

    /**
     * Sends {@code packet} to {@code recipient} over the UDP transport, wrapped with the recipient's own
     * secret/encryption/address. No-op if the recipient has no UDP address yet.
     */
    public void sendTo(@NotNull VoiceServerSession recipient, @NotNull SourceAudioPacket packet) {
        InetSocketAddress address = recipient.getLastAddress();
        if (address == null) return;

        transport.send(packet, recipient.getSecret(), recipient.getEncryption(), address);
    }
}
