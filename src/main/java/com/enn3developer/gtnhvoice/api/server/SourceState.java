package com.enn3developer.gtnhvoice.api.server;

import com.enn3developer.gtnhvoice.api.server.group.RecipientSelection;
import com.enn3developer.gtnhvoice.api.server.group.RoutingContext;

/**
 * The sourceState values groups stamp on outgoing audio ({@link RecipientSelection#send},
 * {@link RoutingContext#sendTo}), deciding per packet how the recipient plays the frame back:
 * {@link #POSITIONAL} applies the packet's source position (gain attenuates with distance), {@link #FLAT} plays
 * at full gain with no spatialization. Positional is deliberately the zero/legacy wire value: peers that predate
 * the flag always sent 0, so a version-skewed pairing degrades to the proximity behavior it always had instead of
 * silently flattening all audio. Decided per packet by the group routing the frame, so a speaker switching groups
 * mid-stream flips modes seamlessly.
 */
public final class SourceState {

    private SourceState() {}

    /** Plain positional/proximity playback: no flags set - the legacy wire value. */
    public static final byte POSITIONAL = 0;

    /** Bit 0: flat playback - full gain, no spatialization, the packet's source position is ignored. */
    public static final byte FLAT = 0b0000_0001;
}
