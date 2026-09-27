package com.enn3developer.gtnhvoice.api.client;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/**
 * An addon-owned playback source - audio the addon produces itself (a radio, a jukebox, a TTS voice) played
 * through the mod's OpenAL context exactly like a speaking player: its own AL source, positioned or flat, under
 * the voice master volume, silenced by the settings GUI's mic monitor. Created through
 * {@link IVoiceAddon#source()}.
 * <p>
 * Feeding: {@link #write} takes {@link VoiceFormat} frames (mono, 48kHz, 16-bit, {@link VoiceFormat#FRAME_SAMPLES}
 * samples) and BLOCKS while the source's bounded buffer is full. The buffer drains at the OpenAL playback rate, so
 * a producer thread that simply decodes and writes in a loop is paced to real time with no clock of its own. Never
 * write from the client (game) thread or the audio thread.
 * <p>
 * Durability: like registration bundles, the handle survives voice session disconnects and reconnects. With no
 * session up, {@link #write} returns {@code false} immediately - producers must back off rather than spin - and
 * the first write of the next session recreates the AL source with the handle's current gain, mode and position.
 * Output-device/HRTF rebuilds are ridden out the same way.
 * <p>
 * Differences from player sources: frames do NOT pass through {@link IPlaybackPcmFilter}s (those are for incoming
 * voice - apply your own DSP before writing), and addon sources never appear in the HUD. They DO fire
 * {@link IAudioLifecycleListener#sourceCreated}/{@code sourceDestroying} under {@link #id()}, and
 * {@link IVoiceAddon#sourceMetadata} answers for them - so listeners must not assume every source id is a
 * player's UUID.
 * <p>
 * Thread-safe: setters may be called from any thread, concurrently with a blocked {@link #write}.
 */
public interface IAddonSource extends AutoCloseable {

    /** The source id - random, stable for the handle's lifetime, never a player's UUID. */
    UUID id();

    /**
     * Queues one frame, blocking while the buffer is full. The frame is copied, so the caller may reuse its
     * array.
     *
     * @return {@code true} once queued; {@code false} when no voice session is running, or the session ended or
     *         this source was closed while blocked - the frame is then dropped
     * @throws IllegalArgumentException if {@code frame} is not {@link VoiceFormat#FRAME_SAMPLES} samples
     * @throws IllegalStateException    if this source was already closed when called
     * @throws InterruptedException     if interrupted while blocked
     */
    boolean write(@NotNull short[] frame) throws InterruptedException;

    /**
     * Drops every frame queued but not yet played - e.g. on a track change or a seek, so the new audio starts
     * immediately instead of after the buffer drains. No-op with no session.
     */
    void flush();

    /**
     * Frames queued by {@link #write} and not yet handed to OpenAL - multiply by
     * {@link VoiceFormat#FRAME_MILLIS} for the buffering latency, e.g. to compensate a synced playback position.
     * OpenAL's own buffers add up to ~120ms on top. {@code 0} with no session.
     */
    int bufferedFrames();

    /** Absolute world position of a positional source; applied within one audio-thread iteration. */
    void setPosition(double x, double y, double z);

    /** Switches between positional and flat playback - see {@link IAddonSourceBuilder#positional}. */
    void setPositional(boolean positional);

    /**
     * Live gain update, {@code 0..2}.
     *
     * @throws IllegalArgumentException if {@code gain} is outside {@code 0..2}
     */
    void setGain(float gain);

    /** Whether the source currently exists in a live session, i.e. has been written since the session began. */
    boolean isLive();

    /**
     * Destroys the AL source and permanently closes the handle; a {@link #write} blocked concurrently returns
     * {@code false}. Idempotent.
     */
    @Override
    void close();
}
