package com.enn3developer.gtnhvoice.client.api;

import java.util.UUID;

import com.enn3developer.gtnhvoice.api.client.IPlaybackPcmFilter;

/**
 * Wraps one playback filter (raw {@link IPlaybackPcmFilter} or a {@link ChainPlaybackFilter}) with the bundle's
 * {@link FilterGate} - the single place the runtime enable/disable check lives on the playback path. When the
 * gate is off, {@link #process(UUID, short[])} returns the frame untouched BEFORE the delegate runs; when on, it
 * just delegates. Every filter in a bundle shares the one gate, so {@code IRegistration.setFilterEnabled(false)}
 * short-circuits them all at once, while the bundle's lifecycle listeners keep firing (per-source pipeline
 * cleanup is wired off those, not through this wrapper, so it runs regardless of the gate). The wrapper never
 * catches: a delegate that throws propagates to the playback adapter's failure isolation exactly as an unwrapped
 * filter would.
 */
final class GatedPlaybackFilter implements IPlaybackPcmFilter {

    private final FilterGate gate;
    private final IPlaybackPcmFilter delegate;

    GatedPlaybackFilter(FilterGate gate, IPlaybackPcmFilter delegate) {
        this.gate = gate;
        this.delegate = delegate;
    }

    @Override
    public short[] process(UUID sourceId, short[] frame) {
        if (!gate.isEnabled()) return frame;
        return delegate.process(sourceId, frame);
    }
}
