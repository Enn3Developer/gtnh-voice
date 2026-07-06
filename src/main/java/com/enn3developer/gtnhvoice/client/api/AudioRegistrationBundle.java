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
 * only filters), the playback filters as registered (an immutable snapshot, possibly empty), the auxiliary-sends
 * requirement this bundle asked for ({@code 0} when it asked for none), the {@link ChainPlaybackFilter}
 * instances this bundle's {@code playbackChain(...)} recipes compiled to (an immutable snapshot, empty for
 * raw-only bundles - the bridge drives their per-source pipeline cleanup off its own isolated listener, NOT the
 * addon's), and the {@link FilterGate} the whole bundle shares. Exactly the shape the bridging layer iterates -
 * listener to the session's lifecycle registry, filters (each wrapped in the gate) to its PCM filter registry,
 * the sends folded into {@link ClientApiBackend#effectiveAuxiliarySends()} - without reshaping. The gate is the
 * one {@code IRegistration.setFilterEnabled} flips.
 */
@Desugar
record AudioRegistrationBundle(String addonName, @Nullable IAudioLifecycleListener listener,
    List<IPlaybackPcmFilter> playbackFilters, int auxiliarySends, List<ChainPlaybackFilter> chainFilters,
    FilterGate gate) {}
