package com.enn3developer.gtnhvoice.client;

import com.enn3developer.gtnhvoice.core.audio.filter.rnnoise.NoiseSuppressionFilter;

/**
 * Internal per-frame PCM processing hook on the outgoing capture path - the seam a future public addon API
 * (voice changers, custom DSP on mic audio) will build on, the capture-side counterpart of
 * {@code PlaybackPcmFilter}. Filters are registered globally on the {@link CapturePcmFilterChain} owned by
 * {@link VoiceClientManager}, so registrations survive disconnect/reconnect cycles without re-registration.
 * <p>
 * Chain position: filters run inside {@link CaptureSendWorker}'s loop AFTER the built-in
 * {@link NoiseSuppressionFilter} denoise (which stays a hardcoded first stage, outside this chain) and BEFORE
 * the {@link ActivationGate}. That ordering is deliberate: filters receive clean speech rather than raw mic
 * noise, and the gate measures what will actually be transmitted - a filter that quiets or mutes the signal
 * correctly closes the gate instead of transmitting shaped noise. The flip side: filters run once per polled
 * mic frame whether or not the gate ends up open.
 * <p>
 * Threading contract: {@link #process} runs on the capture-send worker thread. There is exactly one local
 * microphone, so calls are strictly sequential - per-filter state needs no locking. No sourceId parameter for
 * the same reason.
 * <p>
 * Failure isolation: a filter that throws, returns {@code null}, or returns a wrong-length array is skipped for
 * that frame (the frame continues unfiltered into the rest of the chain) with a throttled error log - one broken
 * filter must not kill the mic.
 */
interface CapturePcmFilter {

    /**
     * Processes one captured mic frame. The filter may mutate {@code frame} in place and return it, or return a
     * replacement array - either way the returned array MUST be 960 samples, and it becomes the input to the
     * next filter in the chain (and ultimately what the activation gate evaluates and, if open, what gets
     * encoded and sent).
     *
     * @param frame 960 samples of mono 16-bit PCM at 48kHz (one 20ms frame), already denoised if the built-in
     *              noise suppression is enabled
     * @return the processed 960-sample frame; {@code null} or a wrong-length array skips this filter for this
     *         frame
     */
    short[] process(short[] frame);
}
