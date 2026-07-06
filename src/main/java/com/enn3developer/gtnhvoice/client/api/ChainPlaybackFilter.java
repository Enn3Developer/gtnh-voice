package com.enn3developer.gtnhvoice.client.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.enn3developer.gtnhvoice.api.client.IPlaybackPcmFilter;

/**
 * A compiled {@link IPcmChain} recipe as a playback filter - what {@code IAudioRegistrationBuilder.playbackChain(...)}
 * produces, plugged into the same seam a raw {@link IPlaybackPcmFilter} occupies. Playback frames arrive per
 * voice source, concurrently across sources, so this keeps ONE {@link PcmChainPipeline} per {@code sourceId} in a
 * {@link ConcurrentHashMap}, created lazily on that source's first frame. Because each source gets its own
 * pipeline, the recipe's stateful stages get independent per-speaker delay lines by construction - which is
 * exactly {@link IPlaybackPcmFilter}'s concurrency contract (concurrent across sources, sequential per source)
 * satisfied for free.
 * <p>
 * Per-source state is released off the source lifecycle: the bundle's builder wires {@link #evict(UUID)} to
 * {@code sourceDestroying} and {@link #clear()} to {@code contextDestroying}, so a pipeline dies with its
 * speaker and nothing leaks across a session. Failure isolation is inherited - a throwing stage propagates out
 * of {@link #process} and the wrapping playback adapter passes the frame through with a throttled log.
 */
final class ChainPlaybackFilter implements IPlaybackPcmFilter {

    private final List<Supplier<FrameStage>> factories;
    private final Map<UUID, PcmChainPipeline> pipelines = new ConcurrentHashMap<>();

    ChainPlaybackFilter(List<Supplier<FrameStage>> factories) {
        this.factories = factories;
    }

    @Override
    public short[] process(UUID sourceId, short[] frame) {
        // Fast path: a live source's pipeline already exists on all but its first frame, so a plain get avoids
        // computeIfAbsent's capturing-lambda overhead per frame. Fall into the atomic build only on the miss.
        PcmChainPipeline pipeline = pipelines.get(sourceId);
        if (pipeline == null) pipeline = pipelines.computeIfAbsent(sourceId, id -> new PcmChainPipeline(factories));
        return pipeline.process(frame);
    }

    /** Drops one source's pipeline (its speaker is gone); the next frame for a new source rebuilds lazily. */
    void evict(UUID sourceId) {
        pipelines.remove(sourceId);
    }

    /** Drops every pipeline - the context (and all its sources) is tearing down. */
    void clear() {
        pipelines.clear();
    }
}
