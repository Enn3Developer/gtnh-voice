package com.enn3developer.gtnhvoice.client.api;

import com.enn3developer.gtnhvoice.api.client.ICapturePcmFilter;

/**
 * Wraps one capture filter (raw {@link ICapturePcmFilter} or a {@link ChainCaptureFilter}) with the bundle's
 * {@link FilterGate} - the single place the runtime enable/disable check lives on the capture path. When the
 * gate is off, {@link #process(short[])} returns the frame untouched BEFORE the delegate runs; when on, it just
 * delegates. Every filter in a bundle shares the one gate, so {@code IRegistration.setFilterEnabled(false)}
 * short-circuits them all at once. The wrapper never catches: a delegate that throws propagates to the capture
 * adapter's failure isolation exactly as an unwrapped filter would.
 * <p>
 * The wrapper carries no per-session reset relay: a chain's per-session pipeline reset is driven at the bundle
 * level by {@link ClientApiBackend#resetCaptureChains()}, which resets the {@link ChainCaptureFilter} instances
 * directly, so this decorator stays a pure gate.
 */
final class GatedCaptureFilter implements ICapturePcmFilter {

    private final FilterGate gate;
    private final ICapturePcmFilter delegate;

    GatedCaptureFilter(FilterGate gate, ICapturePcmFilter delegate) {
        this.gate = gate;
        this.delegate = delegate;
    }

    @Override
    public short[] process(short[] frame) {
        if (!gate.isEnabled()) return frame;
        return delegate.process(frame);
    }
}
