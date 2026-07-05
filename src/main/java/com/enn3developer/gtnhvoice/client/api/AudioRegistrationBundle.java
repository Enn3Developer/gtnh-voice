package com.enn3developer.gtnhvoice.client.api;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.api.client.IAudioLifecycleListener;
import com.enn3developer.gtnhvoice.api.client.IPlaybackPcmFilter;
import com.github.bsideup.jabel.Desugar;

/**
 * One durable playback-side registration as stored by {@link ClientApiBackend}: the addon name (attribution
 * only, never identity - bundles are added/removed by reference and names may repeat), the single listener
 * assembled from the builder's whole listeners and per-event callbacks ({@code null} when the bundle registered
 * only filters), and the playback filters (an immutable snapshot, possibly empty). Exactly the shape the later
 * bridging task iterates - listener to the session's lifecycle registry, filters to its PCM filter registry -
 * without reshaping.
 */
@Desugar
record AudioRegistrationBundle(String addonName, @Nullable IAudioLifecycleListener listener,
    List<IPlaybackPcmFilter> playbackFilters) {
}
