package com.enn3developer.gtnhvoice.api.server;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.api.server.group.IGroup;
import com.enn3developer.gtnhvoice.api.server.group.RoutingContext;

/**
 * The read-only session view the routing API exposes: player identity plus whether the session is reachable over
 * UDP, and nothing more. The concrete session (credentials, transport state) stays internal to the server - groups
 * and filter predicates only ever see this interface, and {@link RoutingContext#sendTo} resolves the concrete
 * session behind the scenes. Implementations are read on the UDP/Netty thread under the {@link IGroup#route}
 * contract.
 */
public interface IVoiceSession {

    /** The session player's UUID - the routing identity, stable for the session's lifetime. */
    @NotNull
    UUID getPlayerUuid();

    /** The session player's name, as established at handshake time. */
    @NotNull
    String getPlayerName();

    /**
     * Whether this session has a UDP address to send to yet. A session without one exists over the control
     * channel but is unreachable for audio - {@link RoutingContext#sendTo} silently drops packets to it.
     */
    boolean hasUdpAddress();
}
