package com.enn3developer.gtnhvoice.client.playback;

import com.enn3developer.gtnhvoice.api.client.ISourceMetadata;
import com.github.bsideup.jabel.Desugar;

/**
 * Immutable point-in-time snapshot of one voice source's spatial metadata: the speaker's last known absolute
 * world position and whether the source currently plays positionally (proximity/3D) or flat. Produced by
 * {@link PlaybackManager#sourceMetadata} - see there for the consistency and freshness contract. A detached
 * value, not a live view: it never changes after construction and holds no references into playback state.
 * <p>
 * Implements the public {@link ISourceMetadata} as an API-backing seam, so
 * {@link PlaybackManager#sourceMetadataFor} can hand instances to addons unwrapped - the record itself stays
 * package-private.
 */
@Desugar
record SourceMetadata(double x, double y, double z, boolean positional) implements ISourceMetadata {
}
