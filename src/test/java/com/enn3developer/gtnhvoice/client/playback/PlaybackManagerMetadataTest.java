package com.enn3developer.gtnhvoice.client.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Exercises {@link PlaybackManager#sourceMetadata} against the documented contract. State is seeded directly
 * through the package-private {@code *View()} accessors rather than {@code createSource}, whose
 * {@code isPlaying()} guard would demand a live playback thread (and thus an AL device) - the same seeding
 * trick {@link PlaybackLifecycleListenerTest} uses on {@code PlaybackThread.sourceChannels}.
 */
class PlaybackManagerMetadataTest {

    private static final UUID SOURCE_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SOURCE_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static PlaybackManager managerWithSource(UUID sourceId, double x, double y, double z,
        boolean positional) {
        PlaybackManager manager = new PlaybackManager();
        manager.positionsView()
            .put(sourceId, new double[] { x, y, z });
        manager.positionalModesView()
            .put(sourceId, positional);
        return manager;
    }

    @Test
    void knownSourceReturnsLatestPositionAndMode() {
        PlaybackManager manager = managerWithSource(SOURCE_A, 1.5, 64.0, -7.25, false);

        Optional<SourceMetadata> metadata = manager.sourceMetadata(SOURCE_A);

        assertTrue(metadata.isPresent());
        assertEquals(1.5, metadata.get().x());
        assertEquals(64.0, metadata.get().y());
        assertEquals(-7.25, metadata.get().z());
        assertFalse(metadata.get().positional());
    }

    @Test
    void wholesalePositionReplacementIsReflectedOnNextQuery() {
        PlaybackManager manager = managerWithSource(SOURCE_A, 0, 0, 0, true);

        // The production write path: updateSourcePosition swaps the whole array, never mutates in place.
        manager.positionsView()
            .put(SOURCE_A, new double[] { 10, 20, 30 });

        SourceMetadata metadata = manager.sourceMetadata(SOURCE_A)
            .orElseThrow(AssertionError::new);
        assertEquals(10, metadata.x());
        assertEquals(20, metadata.y());
        assertEquals(30, metadata.z());
    }

    @Test
    void unknownSourceIsEmpty() {
        PlaybackManager manager = managerWithSource(SOURCE_A, 1, 2, 3, true);

        assertFalse(manager.sourceMetadata(SOURCE_B).isPresent());
    }

    @Test
    void removedSourceIsEmptyAgain() {
        PlaybackManager manager = managerWithSource(SOURCE_A, 1, 2, 3, true);

        // What destroySource does to these maps (the AL teardown half needs a live thread).
        manager.positionsView()
            .remove(SOURCE_A);
        manager.positionalModesView()
            .remove(SOURCE_A);

        assertFalse(manager.sourceMetadata(SOURCE_A).isPresent());
    }

    @Test
    void halfRemovedSourceIsEmptyNotCorrupt() {
        // A racing destroySource may have taken the mode but not yet the position when we read - the query
        // must report the source as gone, not invent a mode for it.
        PlaybackManager manager = managerWithSource(SOURCE_A, 1, 2, 3, true);
        manager.positionalModesView()
            .remove(SOURCE_A);

        assertFalse(manager.sourceMetadata(SOURCE_A).isPresent());
    }

    @Test
    void returnedSnapshotIsDetachedFromLaterUpdates() {
        PlaybackManager manager = managerWithSource(SOURCE_A, 1, 2, 3, true);

        SourceMetadata before = manager.sourceMetadata(SOURCE_A)
            .orElseThrow(AssertionError::new);
        manager.positionsView()
            .put(SOURCE_A, new double[] { 99, 98, 97 });
        manager.positionalModesView()
            .put(SOURCE_A, false);

        assertEquals(1, before.x());
        assertEquals(2, before.y());
        assertEquals(3, before.z());
        assertTrue(before.positional());
    }
}
