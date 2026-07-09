package com.enn3developer.gtnhvoice.api.server;

/**
 * The read-only view of one inbound audio frame the routing API exposes: the speaker's encoded payload plus its
 * sequence number, and nothing more - the wire packet carrying them stays internal, and {@code RoutingContext.sendTo}
 * builds the outgoing packets from this frame behind the scenes. Read on the UDP/Netty thread under the
 * {@code IGroup.route} contract.
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
}
