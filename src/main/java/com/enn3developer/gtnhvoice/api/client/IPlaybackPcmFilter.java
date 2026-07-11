package com.enn3developer.gtnhvoice.api.client;

import java.util.UUID;

/**
 * Per-frame PCM hook on incoming voice (radio effects, custom DSP on what other players say). Registered
 * exclusively through an {@link IVoiceAddon#audio()} bundle's {@code playbackFilter(...)}. Filters run on
 * the receive path, before the frame is queued for the audio thread - deliberately NOT on the audio thread, so
 * addon DSP can never eat into the audio pump's time budget.
 * <p>
 * Threading: {@link #process} runs on the network/decode thread that received the frame - not the audio thread,
 * not the client (game) thread. Calls for a single {@code sourceId} are sequential (one decoder per source), but
 * calls for different sources may be concurrent: an implementation that keys its state by {@code sourceId} needs
 * no locking, one that shares state across sources does. Clean up per-source state via the source lifecycle
 * events on {@link IAudioLifecycleListener}.
 * <p>
 * Every playback frame passes through, including packet-loss-concealment frames synthesized for network gaps -
 * filters cannot tell the two apart, by design: concealment output is meant to be a seamless stand-in for the
 * lost audio, and a chain that treated it specially would make losses audible.
 * <p>
 * Chain order across all registrations is registration order, each filter's output feeding the next. Failure
 * isolation: a filter that throws, returns {@code null} or returns a wrong-length array is skipped for that
 * frame - the frame continues unfiltered into the rest of the chain - with a throttled error log; one broken
 * filter can neither mute voice nor kill the receive path.
 */
@FunctionalInterface
public interface IPlaybackPcmFilter {

    /**
     * Processes one decoded playback frame for {@code sourceId}. Mutate {@code frame} in place and return it, or
     * return a replacement array - either way the result MUST be {@link VoiceFormat#FRAME_SAMPLES} samples, and
     * it becomes the input to the next filter in the chain (and ultimately what gets played).
     *
     * @param sourceId the voice source this frame belongs to (the speaking player's UUID)
     * @param frame    {@link VoiceFormat#FRAME_SAMPLES} samples of mono 16-bit PCM at
     *                 {@link VoiceFormat#SAMPLE_RATE}Hz - one {@link VoiceFormat#FRAME_MILLIS}ms frame
     * @return the processed frame; {@code null} or a wrong-length array skips this filter for this frame
     */
    short[] process(UUID sourceId, short[] frame);
}
