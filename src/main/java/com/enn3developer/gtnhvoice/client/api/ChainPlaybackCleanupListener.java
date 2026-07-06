package com.enn3developer.gtnhvoice.client.api;

import java.util.List;
import java.util.UUID;

import com.enn3developer.gtnhvoice.api.client.IAudioLifecycleListener;

/**
 * The mod-owned lifecycle listener that releases a playback-chain bundle's per-source pipelines - deliberately a
 * SEPARATE listener registration from the bundle's addon listener, so it never shares a failure domain with
 * addon code. Were this folded into the bundle's own {@link CompositeLifecycleListener} (which has no per-part
 * isolation) an addon part that threw in {@code sourceDestroying}/{@code contextDestroying} would abort the
 * cleanup and leak pipelines in the durable {@link ChainPlaybackFilter}; attached on its own it is isolated by
 * the same per-listener catch every addon listener already gets.
 * <p>
 * It evicts a source's pipeline on BOTH {@code sourceDestroying} (the speaker left) and {@code sourceCreated}
 * (the speaker is (re)appearing): the second is the belt-and-suspenders drop for any stale entry a decode-thread
 * frame in flight re-inserted after eviction, or one that outlived a session whose {@code contextDestroying}
 * never fired - so a returning UUID always resumes from a fresh delay line. {@code contextDestroying} clears
 * every source at once as the context tears down. Fires regardless of the bundle's filter gate: cleanup is a
 * lifecycle concern, not a filtering one.
 */
final class ChainPlaybackCleanupListener implements IAudioLifecycleListener {

    private final List<ChainPlaybackFilter> chainFilters;

    ChainPlaybackCleanupListener(List<ChainPlaybackFilter> chainFilters) {
        this.chainFilters = chainFilters;
    }

    @Override
    public void sourceCreated(UUID sourceId, int sourceHandle) {
        for (ChainPlaybackFilter filter : chainFilters) {
            filter.evict(sourceId);
        }
    }

    @Override
    public void sourceDestroying(UUID sourceId, int sourceHandle) {
        for (ChainPlaybackFilter filter : chainFilters) {
            filter.evict(sourceId);
        }
    }

    @Override
    public void contextDestroying() {
        for (ChainPlaybackFilter filter : chainFilters) {
            filter.clear();
        }
    }
}
