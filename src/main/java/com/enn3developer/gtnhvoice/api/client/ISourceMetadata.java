package com.enn3developer.gtnhvoice.api.client;

/**
 * Point-in-time spatial snapshot of one voice source: the speaker's last known absolute world position and
 * whether the source currently plays positionally (proximity/3D) or flat. A detached value, never a live view -
 * it does not change after being returned and holds no references into playback state. Obtained via
 * {@link IVoiceAddon#sourceMetadata}.
 * <p>
 * A voice source id IS the speaking player's UUID, so consumers needing the player entity or name resolve it
 * themselves - this query deliberately duplicates no roster data. Position and positional flag follow the audio:
 * each frame's values apply as that frame plays out, not when its packet arrives, so they match what is being
 * heard. Fresh-source edge: a source reports position (0, 0, 0) until its first frame plays out (one jitter-buffer
 * delay after creation, typically 20-100ms), so a source at exactly the origin may simply be brand new - treat it
 * with suspicion or wait a tick before raycasting from it.
 * <p>
 * Consistency is deliberately relaxed: the position triple is always internally consistent, but the position and
 * the positional flag may come from instants up to one frame (~20ms) apart - plenty for spatial-audio consumers,
 * and doing better would mean locking the hot receive path.
 */
public interface ISourceMetadata {

    /** The speaker's last known absolute world X coordinate. */
    double x();

    /** The speaker's last known absolute world Y coordinate. */
    double y();

    /** The speaker's last known absolute world Z coordinate. */
    double z();

    /** Whether the source currently plays positionally (proximity/3D) rather than flat (full gain, no spatialization). */
    boolean positional();
}
