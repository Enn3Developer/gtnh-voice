package com.enn3developer.gtnhvoice.api.client;

/**
 * Single-use fluent builder for an addon-owned playback source, opened by {@link IVoiceAddon#source()}. Every
 * setter is optional - the defaults describe a positional source at full gain - and {@link #create()} is the
 * terminal step. Not thread-safe: a builder is a short-lived, single-caller object; only the created
 * {@link IAddonSource} is shared.
 */
public interface IAddonSourceBuilder {

    /** Default {@link #distance(int)}, in blocks. */
    int DEFAULT_DISTANCE = 32;

    /** Default {@link #bufferMillis(int)}. */
    int DEFAULT_BUFFER_MILLIS = 200;

    /**
     * The distance in blocks past which a positional source is inaudible ({@code AL_MAX_DISTANCE}). Fixed for
     * the source's lifetime.
     *
     * @throws IllegalArgumentException if {@code blocks} is not positive
     */
    IAddonSourceBuilder distance(int blocks);

    /**
     * Initial source gain, {@code 0..2} (the same range as the per-player volume slider); adjustable later
     * through {@link IAddonSource#setGain(float)}.
     *
     * @throws IllegalArgumentException if {@code gain} is outside {@code 0..2}
     */
    IAddonSourceBuilder gain(float gain);

    /**
     * Initial mode: positional (world-positioned, distance-attenuated - the default) or flat (full gain
     * regardless of where the listener stands); switchable later through {@link IAddonSource#setPositional}.
     */
    IAddonSourceBuilder positional(boolean positional);

    /**
     * How much audio {@link IAddonSource#write} may queue ahead of OpenAL before it blocks, {@code 40..1000}ms,
     * rounded up to whole {@link VoiceFormat#FRAME_MILLIS} frames. Larger rides out producer scheduling hiccups;
     * smaller keeps {@link IAddonSource#flush()} and position-synced playback tighter.
     *
     * @throws IllegalArgumentException if {@code millis} is outside {@code 40..1000}
     */
    IAddonSourceBuilder bufferMillis(int millis);

    /**
     * Creates the source. Nothing touches OpenAL yet: the AL source is created lazily by the first
     * {@link IAddonSource#write} of each voice session.
     *
     * @throws IllegalStateException if this builder was already consumed
     */
    IAddonSource create();
}
