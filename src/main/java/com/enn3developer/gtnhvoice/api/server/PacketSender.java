package com.enn3developer.gtnhvoice.api.server;

import java.net.InetSocketAddress;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.api.encryption.Encryption;
import com.enn3developer.gtnhvoice.core.proto.packets.Packet;

/**
 * The single seam through which group routing emits UDP packets - shaped exactly like
 * {@code UdpTransportServer#send} so the production wiring is just a method reference, while tests substitute a
 * capturing lambda instead of standing up a real Netty transport. Implementations must be safe to call from the
 * UDP/Netty thread and must not block.
 */
@FunctionalInterface
public interface PacketSender {

    void send(@NotNull Packet<?> packet, @NotNull UUID sessionId, @NotNull Encryption encryption,
        @NotNull InetSocketAddress recipient);
}
