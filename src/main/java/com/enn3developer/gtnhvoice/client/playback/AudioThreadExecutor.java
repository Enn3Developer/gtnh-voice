package com.enn3developer.gtnhvoice.client.playback;

/**
 * Internal seam for marshalling work onto the playback thread - the only thread allowed to touch this mod's
 * OpenAL device/context (see {@link PlaybackThread}). A future public {@code runOnAudioThread} addon API will
 * wrap an instance of this rather than exposing {@link PlaybackThread} directly; until then it stays
 * package-private with {@link PlaybackManager#audioThreadExecutor()} as the only way to obtain one.
 */
interface AudioThreadExecutor {

    /**
     * Submits {@code command} (non-null, or {@link NullPointerException}) to run on the audio thread's next
     * loop iteration, serialized with every other AL call that thread makes. Returns {@code false} when the
     * audio thread is not accepting commands (playback not running, or shutting down) - the command is then
     * dropped, never queued. A {@code true} return means accepted, not guaranteed: playback tearing down
     * concurrently with the submission may discard an accepted command unrun. A command that throws is isolated
     * and logged by the audio thread; it cannot break playback for anyone else.
     */
    boolean execute(Runnable command);
}
