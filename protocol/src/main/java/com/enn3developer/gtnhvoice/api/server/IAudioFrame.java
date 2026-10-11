package com.enn3developer.gtnhvoice.api.server;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/**
 * The read-only view of one inbound audio frame the routing API exposes: the speaker's encoded payload, its sequence
 * number and the activation it claims, and nothing more - the wire packet carrying them stays internal, and
 * {@code RoutingContext.sendTo} builds the outgoing packets from this frame behind the scenes. Read on the UDP/Netty
 * thread under the {@code IGroup.route} contract.
 * <p>
 * Lives in the shared {@code :protocol} module (with the wire packets that implement it) rather than the mod, so its
 * fully-qualified name is unchanged for API consumers; the mod's routing types ({@code IGroup}/{@code RoutingContext})
 * are only referenced in prose here to avoid a back-dependency from {@code :protocol} onto the mod.
 */
public interface IAudioFrame {

    /** Monotonic per-speaker sequence number of this frame, carried through to every forwarded packet. */
    long getSequenceNumber();

    /** The encoded (Opus) audio payload, forwarded verbatim to every recipient. */
    byte[] getData();

    /**
     * The activation the speaker's client says this frame was sent under: {@link ActivationIds#DEFAULT} for
     * ordinary voice (voice activation or the push-to-talk key), or {@link ActivationIds#of} of the client addon
     * activation that was keyed - while several are keyed, the most recently keyed one. Lets a group route only
     * what the player meant for it, e.g. a radio group forwarding only frames sent while the radio was keyed and
     * leaving ordinary voice to proximity.
     * <p>
     * The id is a client claim: any client can stamp any id on any frame. A group may treat it as intent within
     * memberships the server already granted, never as authorization - decide who may speak to whom from server
     * state (group membership, items, permissions), and use the id only to choose among routes the speaker was
     * already entitled to. Ids no current client sends (random ids from clients that predate activations) match
     * neither {@link ActivationIds#DEFAULT} nor any derived id.
     * <p>
     * Defaults to {@link ActivationIds#DEFAULT}, so frame implementations written before activations existed (test
     * doubles) keep compiling and read as ordinary voice; the mod's inbound frames always report the wire value.
     * Read on the UDP/Netty thread like the rest of the frame.
     */
    default @NotNull UUID getActivationId() {
        return ActivationIds.DEFAULT;
    }
}
