package com.enn3developer.gtnhvoice.api.client;

import org.jetbrains.annotations.NotNull;

/**
 * Per-frame PCM hook on incoming voice that also sees how the frame was routed - the {@link IPlaybackFrame}
 * context: speaker, internal and display name of the routing group, positional flag and the position the frame plays
 * from. Registered exclusively through an {@link IVoiceAddon#audio()} bundle's {@code playbackFrameFilter(...)}.
 * Reach for it over {@link IPlaybackPcmFilter} when the effect depends on the routing, e.g.:
 *
 * <pre>{@code
 * addon.audio()
 *     .playbackFrameFilter((frame, pcm) -> "radio".equals(frame.groupName()) ? crackle.apply(frame.sourceId(), pcm) : pcm)
 *     .done();
 * }</pre>
 *
 * Everything else is {@link IPlaybackPcmFilter}'s contract, unchanged:
 * <ul>
 * <li>Threading: {@link #process} runs on the source's decode thread - not the audio thread, not the client (game)
 * thread. Calls for one {@code frame.sourceId()} are sequential, calls for different sources may be concurrent: key
 * per-source state by {@code sourceId()} (no locking needed) and release it from the source lifecycle events on
 * {@link IAudioLifecycleListener}.</li>
 * <li>Every frame passes through, packet-loss-concealment frames included; those carry the context of the source's
 * most recent real frame - see {@link IPlaybackFrame}.</li>
 * <li>Ordering: frame filters, raw filters and chains of every bundle share ONE chain in registration order, each
 * output feeding the next.</li>
 * <li>Failure isolation: a filter that throws, returns {@code null} or returns a wrong-length array is skipped for
 * that frame - the frame continues unfiltered into the rest of the chain - with a throttled error log attributed to
 * the addon; one broken filter can neither mute voice nor kill the receive path.</li>
 * </ul>
 */
@FunctionalInterface
public interface IPlaybackFrameFilter {

    /**
     * Processes one decoded playback frame. Mutate {@code pcm} in place and return it, or return a replacement
     * array - either way the result MUST be {@link VoiceFormat#FRAME_SAMPLES} samples, and it becomes the input to
     * the next filter in the chain (and ultimately what gets played).
     *
     * @param frame what the frame is and how it was routed; an immutable value, safe to retain
     * @param pcm   {@link VoiceFormat#FRAME_SAMPLES} samples of mono 16-bit PCM at {@link VoiceFormat#SAMPLE_RATE}Hz
     *              - one {@link VoiceFormat#FRAME_MILLIS}ms frame
     * @return the processed frame; {@code null} or a wrong-length array skips this filter for this frame
     */
    short[] process(@NotNull IPlaybackFrame frame, @NotNull short[] pcm);
}
