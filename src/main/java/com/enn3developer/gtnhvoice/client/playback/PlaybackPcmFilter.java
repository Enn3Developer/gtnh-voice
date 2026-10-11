package com.enn3developer.gtnhvoice.client.playback;

import com.enn3developer.gtnhvoice.api.client.IPlaybackFrame;

/**
 * Internal per-frame PCM processing hook on the playback path - the seam a future public addon API (radio
 * effects, custom DSP on incoming voice) will build on. Filters are registered globally on
 * {@link PlaybackManager} and run inside {@link PlaybackManager#submit}, on the decode path, before the frame is
 * offered to the source's queue - deliberately NOT on the playback thread, so addon DSP can never eat into the
 * audio pump's time budget.
 * <p>
 * Threading contract: {@link #process} runs on the network/decode thread that called {@code submit} - not the
 * playback thread, not the client thread. Calls for a single {@code sourceId} are sequential (one decoder per
 * source), but calls for different sources may be concurrent - an implementation that keys its state by
 * {@code sourceId} needs no locking, one that shares state across sources does. Per-source state is the filter's
 * own business: key it off {@code frame.sourceId()} and clean it up via the {@code sourceCreated}/
 * {@code sourceDestroying} events on {@link PlaybackLifecycleListener}.
 * <p>
 * Every playback frame passes through, including Opus packet-loss-concealment frames synthesized for gaps -
 * filters cannot tell the two apart, by design: PLC output is meant to be a seamless stand-in for the lost
 * audio, and a filter chain that treated it specially would make losses audible. A PLC frame carries the
 * {@link IPlaybackFrame} context of the source's last real frame.
 * <p>
 * Failure isolation: a filter that throws, returns {@code null}, or returns a wrong-length array is skipped for
 * that frame (the frame continues unfiltered into the rest of the chain) with a throttled error log - one broken
 * filter can neither mute voice nor kill the receive path.
 */
interface PlaybackPcmFilter {

    /**
     * Processes one decoded playback frame. The filter may mutate {@code pcm} in place and return it, or return a
     * replacement array - either way the returned array MUST be 960 samples, and it becomes the input to the next
     * filter in the chain (and ultimately what gets queued for playback).
     *
     * @param frame the frame's source and routing context
     * @param pcm   960 samples of mono 16-bit PCM at 48kHz (one 20ms frame)
     * @return the processed 960-sample frame; {@code null} or a wrong-length array skips this filter for this
     *         frame
     */
    short[] process(IPlaybackFrame frame, short[] pcm);
}
