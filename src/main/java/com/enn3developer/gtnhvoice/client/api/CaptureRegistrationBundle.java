package com.enn3developer.gtnhvoice.client.api;

import java.util.List;

import com.enn3developer.gtnhvoice.api.client.ICapturePcmFilter;
import com.github.bsideup.jabel.Desugar;

/**
 * One durable capture-side registration as stored by {@link ClientApiBackend}: the addon name (attribution
 * only, never identity), the capture filters as registered (an immutable snapshot, never empty - the builder
 * rejects empty bundles), the {@link ChainCaptureFilter} instances this bundle's {@code chain(...)} recipes
 * compiled to (an immutable snapshot, empty for raw-only bundles - {@link ClientApiBackend#resetCaptureChains()}
 * resets these directly at each new capture session, no per-decorator relay), and the {@link FilterGate} the
 * whole bundle shares. The {@link AddonSessionBridge} wraps each filter in the gate when it wires them onto the
 * capture PCM filter chain; the gate itself is the one {@code IRegistration.setFilterEnabled} flips.
 */
@Desugar
record CaptureRegistrationBundle(String addonName, List<ICapturePcmFilter> captureFilters,
    List<ChainCaptureFilter> chainFilters, FilterGate gate) {}
