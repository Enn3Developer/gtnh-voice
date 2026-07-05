package com.enn3developer.gtnhvoice.client.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.Config;

/**
 * Exercises {@link PlaybackThread#replayLiveStateTo} without an AL device, on the unstarted-thread pattern
 * used throughout this package: the no-live-context no-op straight off construction, and the
 * contextCreated-then-every-sourceCreated replay order against an adopted fake context (adopt/seeding touch
 * only plain fields, never AL). The race-freedom of replay against real events - both running only inside
 * this thread's serialized command drain - is an on-thread property that can only be proven in-game.
 */
class PlaybackThreadReplayTest {

    private static final UUID SOURCE_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SOURCE_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** Listener recording every callback as one string, so replay content and ordering are assertable. */
    private static final class RecordingListener implements PlaybackLifecycleListener {

        final List<String> events = new ArrayList<>();

        @Override
        public void contextCreated(long deviceHandle) {
            events.add("contextCreated:" + deviceHandle);
        }

        @Override
        public void sourceCreated(UUID sourceId, int sourceHandle) {
            events.add("sourceCreated:" + sourceId + ":" + sourceHandle);
        }
    }

    private static PlaybackThread newUnstartedThread() {
        return new PlaybackThread(new PlaybackManager(), null, Config.HrtfMode.AUTO);
    }

    private static SourceChannelPool.SourceChannel channelWithHandle(int alSource) {
        return new SourceChannelPool.SourceChannel(
            alSource,
            new int[0],
            new ArrayDeque<>(),
            new LinkedBlockingQueue<>());
    }

    @Test
    void replayIsNoOpWithoutLiveContext() {
        PlaybackThread thread = newUnstartedThread();
        RecordingListener listener = new RecordingListener();

        // Seeded channels must not leak through the guard either - no context means nothing to replay.
        thread.channelPool.sourceChannels.put(SOURCE_A, channelWithHandle(11));
        thread.replayLiveStateTo(listener);

        assertTrue(listener.events.isEmpty(), "no live context must mean a complete no-op");
    }

    @Test
    void replayAnnouncesContextThenEverySource() {
        PlaybackThread thread = newUnstartedThread();
        // Fake handles committed through the same adopt() the real startup uses - plain field writes, no AL.
        thread.deviceContext.adopt(7L, 9L, null);
        thread.channelPool.sourceChannels.put(SOURCE_A, channelWithHandle(11));
        thread.channelPool.sourceChannels.put(SOURCE_B, channelWithHandle(22));
        RecordingListener listener = new RecordingListener();

        thread.replayLiveStateTo(listener);

        assertEquals(3, listener.events.size());
        assertEquals("contextCreated:7", listener.events.get(0), "the context must be announced first");
        assertTrue(
            listener.events.subList(1, 3)
                .containsAll(
                    Arrays.asList("sourceCreated:" + SOURCE_A + ":11", "sourceCreated:" + SOURCE_B + ":22")),
            "every live channel must be replayed (with its own handle) after the context");
    }

    @Test
    void replayReachesOnlyTheGivenListener() {
        PlaybackManager manager = new PlaybackManager();
        PlaybackThread thread = new PlaybackThread(manager, null, Config.HrtfMode.AUTO);
        thread.deviceContext.adopt(7L, 9L, null);

        RecordingListener alreadyRegistered = new RecordingListener();
        manager.addLifecycleListener(alreadyRegistered);
        thread.replayLiveStateTo(new RecordingListener());

        assertTrue(
            alreadyRegistered.events.isEmpty(),
            "replay targets JUST the catching-up listener - registered listeners saw the real events already");
    }
}
