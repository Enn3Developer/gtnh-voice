package com.enn3developer.gtnhvoice.api.server;

import com.enn3developer.gtnhvoice.api.server.group.IGroup;
import com.enn3developer.gtnhvoice.api.server.group.RoutingContext;

/**
 * The read-only view of one inbound audio frame the routing API exposes: the speaker's encoded payload plus its
 * sequence number, and nothing more - the wire packet carrying them stays internal, and {@link RoutingContext#sendTo}
 * builds the outgoing packets from this frame behind the scenes. Read on the UDP/Netty thread under the
 * {@link IGroup#route} contract.
 */
public interface IAudioFrame {

    /** Monotonic per-speaker sequence number of this frame, carried through to every forwarded packet. */
    long getSequenceNumber();

    /** The encoded (Opus) audio payload, forwarded verbatim to every recipient. */
    byte[] getData();
}
