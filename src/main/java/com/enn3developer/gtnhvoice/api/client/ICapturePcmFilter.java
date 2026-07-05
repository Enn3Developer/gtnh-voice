package com.enn3developer.gtnhvoice.api.client;

/**
 * Per-frame PCM hook on outgoing mic audio (voice changers, custom DSP on what you say). Registered exclusively
 * through an {@link IClientCaptureApi#register} bundle's {@code filter(...)}.
 * <p>
 * Chain position: filters run AFTER the built-in noise suppression (a hardcoded first stage outside this chain)
 * and BEFORE the voice-activation gate. That ordering is deliberate: filters receive clean speech rather than
 * raw mic noise, and the gate measures what will actually be transmitted - a filter that quiets or mutes the
 * signal correctly closes the gate instead of letting shaped noise onto the wire. The flip side: filters run
 * once per polled mic frame whether or not the gate ends up open.
 * <p>
 * Threading: {@link #process} runs on the capture-send worker thread. There is exactly one local microphone, so
 * calls are strictly sequential - per-filter state needs no locking; that is also why there is no source
 * parameter.
 * <p>
 * Chain order across all registrations is registration order, each filter's output feeding the next. Failure
 * isolation: a filter that throws, returns {@code null} or returns a wrong-length array is skipped for that
 * frame - the frame continues unfiltered into the rest of the chain - with a throttled error log; one broken
 * filter must not kill the mic.
 */
@FunctionalInterface
public interface ICapturePcmFilter {

    /**
     * Processes one captured mic frame. Mutate {@code frame} in place and return it, or return a replacement
     * array - either way the result MUST be {@link VoiceFormat#FRAME_SAMPLES} samples, and it becomes the input
     * to the next filter in the chain (and ultimately what the activation gate evaluates and, if open, what gets
     * encoded and sent).
     *
     * @param frame {@link VoiceFormat#FRAME_SAMPLES} samples of mono 16-bit PCM at
     *              {@link VoiceFormat#SAMPLE_RATE}Hz - one {@link VoiceFormat#FRAME_MILLIS}ms frame, already
     *              denoised when the built-in noise suppression is enabled
     * @return the processed frame; {@code null} or a wrong-length array skips this filter for this frame
     */
    short[] process(short[] frame);
}
