package com.enn3developer.gtnhvoice.client.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.junit.jupiter.api.Test;

/**
 * Exercises the {@link PlaybackPcmFilter} chain inside {@link PlaybackManager#submit} against the documented
 * contract: registration-order chaining, sourceId/frame delivery, and isolation of throwing/null/wrong-length
 * filters. Queue entries are seeded directly through the package-private {@code frameQueuesView()} accessor
 * rather than {@code createSource}, whose {@code isPlaying()} guard would demand a live playback thread (and
 * thus an AL device) - the same seeding trick {@link PlaybackManagerMetadataTest} uses on the position maps.
 */
class PlaybackPcmFilterTest {

    private static final UUID SOURCE_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SOURCE_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final int FRAME_SAMPLES = 960;

    private static PlaybackManager managerWithQueue(UUID sourceId) {
        PlaybackManager manager = new PlaybackManager();
        manager.frameQueuesView()
            .put(sourceId, new ArrayBlockingQueue<>(50));
        return manager;
    }

    private static short[] frameFilledWith(short value) {
        short[] frame = new short[FRAME_SAMPLES];
        Arrays.fill(frame, value);
        return frame;
    }

    @Test
    void noFiltersQueuesTheSubmittedFrameUntouched() {
        PlaybackManager manager = managerWithQueue(SOURCE_A);
        short[] frame = frameFilledWith((short) 7);

        manager.submit(SOURCE_A, frame);

        // Same array identity - the no-filter path must not copy or transform.
        assertSame(
            frame,
            manager.frameQueuesView()
                .get(SOURCE_A)
                .poll());
    }

    @Test
    void unknownSourceStaysANoOpWithFiltersRegistered() {
        PlaybackManager manager = managerWithQueue(SOURCE_A);
        List<UUID> seen = new ArrayList<>();
        manager.addPcmFilter((sourceId, frame) -> {
            seen.add(sourceId);
            return frame;
        });

        manager.submit(SOURCE_B, frameFilledWith((short) 1));

        // The registered-source guard comes first: no queue entry, and the filter never even ran.
        assertTrue(seen.isEmpty());
        assertNull(
            manager.frameQueuesView()
                .get(SOURCE_A)
                .poll());
    }

    @Test
    void filterSeesSourceIdAndFrameAndItsOutputIsQueued() {
        PlaybackManager manager = managerWithQueue(SOURCE_A);
        short[] submitted = frameFilledWith((short) 3);
        short[] replacement = frameFilledWith((short) 9);
        List<UUID> seenIds = new ArrayList<>();
        List<short[]> seenFrames = new ArrayList<>();
        manager.addPcmFilter((sourceId, frame) -> {
            seenIds.add(sourceId);
            seenFrames.add(frame);
            return replacement;
        });

        manager.submit(SOURCE_A, submitted);

        assertEquals(1, seenIds.size());
        assertEquals(SOURCE_A, seenIds.get(0));
        assertSame(submitted, seenFrames.get(0));
        assertSame(
            replacement,
            manager.frameQueuesView()
                .get(SOURCE_A)
                .poll());
    }

    @Test
    void twoFiltersApplyInRegistrationOrder() {
        PlaybackManager manager = managerWithQueue(SOURCE_A);
        manager.addPcmFilter((sourceId, frame) -> {
            frame[0] += 1;
            return frame;
        });
        manager.addPcmFilter((sourceId, frame) -> {
            frame[0] *= 10;
            return frame;
        });

        manager.submit(SOURCE_A, frameFilledWith((short) 0));

        // (0 + 1) * 10 - the reverse order would give 0 * 10 + 1 = 1.
        short[] queued = manager.frameQueuesView()
            .get(SOURCE_A)
            .poll();
        assertEquals(10, queued[0]);
    }

    @Test
    void throwingFilterIsSkippedAndTheRestOfTheChainStillApplies() {
        PlaybackManager manager = managerWithQueue(SOURCE_A);
        manager.addPcmFilter((sourceId, frame) -> { throw new IllegalStateException("boom"); });
        manager.addPcmFilter((sourceId, frame) -> {
            frame[0] = 42;
            return frame;
        });

        manager.submit(SOURCE_A, frameFilledWith((short) 0));

        short[] queued = manager.frameQueuesView()
            .get(SOURCE_A)
            .poll();
        assertEquals(42, queued[0]);
    }

    @Test
    void nullReturningFilterIsSkippedLikewise() {
        PlaybackManager manager = managerWithQueue(SOURCE_A);
        manager.addPcmFilter((sourceId, frame) -> null);
        manager.addPcmFilter((sourceId, frame) -> {
            frame[0] = 42;
            return frame;
        });

        manager.submit(SOURCE_A, frameFilledWith((short) 0));

        short[] queued = manager.frameQueuesView()
            .get(SOURCE_A)
            .poll();
        assertEquals(42, queued[0]);
    }

    @Test
    void wrongLengthReturningFilterIsSkippedAndTheOriginalFrameContinues() {
        PlaybackManager manager = managerWithQueue(SOURCE_A);
        short[] submitted = frameFilledWith((short) 5);
        manager.addPcmFilter((sourceId, frame) -> new short[123]);

        manager.submit(SOURCE_A, submitted);

        assertSame(
            submitted,
            manager.frameQueuesView()
                .get(SOURCE_A)
                .poll());
    }

    @Test
    void removedFilterNoLongerRuns() {
        PlaybackManager manager = managerWithQueue(SOURCE_A);
        PlaybackPcmFilter filter = (sourceId, frame) -> {
            frame[0] = 42;
            return frame;
        };
        manager.addPcmFilter(filter);
        manager.removePcmFilter(filter);

        short[] submitted = frameFilledWith((short) 5);
        manager.submit(SOURCE_A, submitted);

        short[] queued = manager.frameQueuesView()
            .get(SOURCE_A)
            .poll();
        assertSame(submitted, queued);
        assertEquals(5, queued[0]);
    }

    @Test
    void dropOldestWhenFullSurvivesFiltering() {
        PlaybackManager manager = new PlaybackManager();
        BlockingQueue<short[]> queue = new ArrayBlockingQueue<>(1);
        manager.frameQueuesView()
            .put(SOURCE_A, queue);
        manager.addPcmFilter((sourceId, frame) -> frame);

        short[] first = frameFilledWith((short) 1);
        short[] second = frameFilledWith((short) 2);
        manager.submit(SOURCE_A, first);
        manager.submit(SOURCE_A, second);

        assertEquals(1, queue.size());
        assertSame(second, queue.poll());
    }
}
