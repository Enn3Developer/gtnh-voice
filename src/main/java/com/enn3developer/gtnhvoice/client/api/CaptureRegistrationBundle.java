package com.enn3developer.gtnhvoice.client.api;

import java.util.List;

import com.enn3developer.gtnhvoice.api.client.ICapturePcmFilter;
import com.github.bsideup.jabel.Desugar;

/**
 * One durable capture-side registration as stored by {@link ClientApiBackend}: the addon name (attribution
 * only, never identity) and the capture filters (an immutable snapshot, never empty - the builder rejects empty
 * bundles). Exactly the shape the later bridging task iterates onto the capture PCM filter chain without
 * reshaping.
 */
@Desugar
record CaptureRegistrationBundle(String addonName, List<ICapturePcmFilter> captureFilters) {
}
