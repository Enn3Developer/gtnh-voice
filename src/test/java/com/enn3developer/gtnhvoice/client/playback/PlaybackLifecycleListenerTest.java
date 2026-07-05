package com.enn3developer.gtnhvoice.client.playback;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.Config;

/**
 * Exercises the {@link PlaybackLifecycleListener} registry and dispatch contracts that don't need an AL device:
 * registration/removal on {@link PlaybackManager}, the {@link PlaybackThread} fire helpers invoking listeners
 * (context and per-source), per-listener Throwable isolation, and {@code fireContextTeardown}'s
 * sources-before-context ordering via a seeded {@code sourceChannels} map. As in {@link PlaybackThreadCommandTest},
 * the thread is deliberately never started, so no OpenAL device is opened - the actual fire sites in
 * run()/performRebuild/createSourceChannel/destroySourceChannel can only be exercised in-game.
 */
class PlaybackLifecycleListenerTest {

    private static final UUID SOURCE_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SOURCE_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /**
     * Listener that just counts invocations of the context callbacks - deliberately implementing ONLY those two,
     * so it doubles as the default-method compatibility proof: a pre-existing implementor must keep compiling and
     * working untouched when per-source events fire past it.
     */
    private static final class CountingListener implements PlaybackLifecycleListener {

        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger destroying = new AtomicInteger();

        @Override
        public void contextCreated(long deviceHandle) {
            created.incrementAndGet();
        }

        @Override
        public void contextDestroying() {
            destroying.incrementAndGet();
        }
    }

    /** Listener recording every callback as one string, so argument correctness and ordering are assertable. */
    private static final class RecordingListener implements PlaybackLifecycleListener {

        final List<String> events = new ArrayList<>();

        @Override
        public void contextCreated(long deviceHandle) {
            events.add("contextCreated");
        }

        @Override
        public void contextDestroying() {
            events.add("contextDestroying");
        }

        @Override
        public void sourceCreated(UUID sourceId, int sourceHandle) {
            events.add("sourceCreated:" + sourceId + ":" + sourceHandle);
        }

        @Override
        public void sourceDestroying(UUID sourceId, int sourceHandle) {
            events.add("sourceDestroying:" + sourceId + ":" + sourceHandle);
        }
    }

    private static PlaybackThread.SourceChannel channelWithHandle(int alSource) {
        return new PlaybackThread.SourceChannel(alSource, new int[0], new ArrayDeque<>(), new LinkedBlockingQueue<>());
    }

    @Test
    void fireHelpersInvokeRegisteredListener() {
        PlaybackManager manager = new PlaybackManager();
        PlaybackThread thread = new PlaybackThread(manager, null, Config.HrtfMode.AUTO);
        CountingListener listener = new CountingListener();
        manager.addLifecycleListener(listener);

        thread.fireContextCreated();
        thread.fireContextDestroying();

        assertEquals(1, listener.created.get());
        assertEquals(1, listener.destroying.get());
    }

    @Test
    void sourceFireHelpersPassSourceIdAndHandle() {
        PlaybackManager manager = new PlaybackManager();
        PlaybackThread thread = new PlaybackThread(manager, null, Config.HrtfMode.AUTO);
        RecordingListener listener = new RecordingListener();
        manager.addLifecycleListener(listener);

        thread.fireSourceCreated(SOURCE_A, 42);
        thread.fireSourceDestroying(SOURCE_A, 42);

        assertEquals(Arrays.asList("sourceCreated:" + SOURCE_A + ":42", "sourceDestroying:" + SOURCE_A + ":42"),
            listener.events);
    }

    @Test
    void contextOnlyListenerIgnoresSourceEvents() {
        // Default-method compatibility: CountingListener predates the per-source events and must neither break
        // compilation (proved by this file compiling) nor react to them at runtime.
        PlaybackManager manager = new PlaybackManager();
        PlaybackThread thread = new PlaybackThread(manager, null, Config.HrtfMode.AUTO);
        CountingListener listener = new CountingListener();
        manager.addLifecycleListener(listener);

        assertDoesNotThrow(() -> thread.fireSourceCreated(SOURCE_A, 7));
        assertDoesNotThrow(() -> thread.fireSourceDestroying(SOURCE_A, 7));

        assertEquals(0, listener.created.get());
        assertEquals(0, listener.destroying.get());
    }

    @Test
    void contextTeardownAnnouncesAllSourcesBeforeContext() {
        PlaybackManager manager = new PlaybackManager();
        PlaybackThread thread = new PlaybackThread(manager, null, Config.HrtfMode.AUTO);
        RecordingListener listener = new RecordingListener();
        manager.addLifecycleListener(listener);

        // Safe off-thread only because the thread was never started - see the class javadoc.
        thread.sourceChannels.put(SOURCE_A, channelWithHandle(11));
        thread.sourceChannels.put(SOURCE_B, channelWithHandle(22));

        thread.fireContextTeardown();

        assertEquals(3, listener.events.size());
        assertEquals("contextDestroying", listener.events.get(2), "contextDestroying must fire last");
        assertTrue(
            listener.events.subList(0, 2)
                .containsAll(
                    Arrays.asList("sourceDestroying:" + SOURCE_A + ":11", "sourceDestroying:" + SOURCE_B + ":22")),
            "every source must be announced (with its own handle) before the context");
    }

    @Test
    void removedListenerNoLongerFires() {
        PlaybackManager manager = new PlaybackManager();
        PlaybackThread thread = new PlaybackThread(manager, null, Config.HrtfMode.AUTO);
        CountingListener listener = new CountingListener();
        manager.addLifecycleListener(listener);
        manager.removeLifecycleListener(listener);

        thread.fireContextCreated();
        thread.fireContextDestroying();

        assertEquals(0, listener.created.get());
        assertEquals(0, listener.destroying.get());
    }

    @Test
    void registrationsSurviveAcrossThreadInstances() {
        // The registry lives on the manager, not the thread - a listener registered once must still be seen by
        // a later PlaybackThread instance, mirroring what a start()/stop() cycle does.
        PlaybackManager manager = new PlaybackManager();
        CountingListener listener = new CountingListener();
        manager.addLifecycleListener(listener);

        new PlaybackThread(manager, null, Config.HrtfMode.AUTO).fireContextCreated();
        new PlaybackThread(manager, null, Config.HrtfMode.AUTO).fireContextCreated();

        assertEquals(2, listener.created.get());
    }

    @Test
    void throwingListenerDoesNotStarveLaterListeners() {
        PlaybackManager manager = new PlaybackManager();
        PlaybackThread thread = new PlaybackThread(manager, null, Config.HrtfMode.AUTO);
        AtomicBoolean secondRan = new AtomicBoolean();

        // The exact failure mode addon listeners hit when their class is missing @Lwjgl3Aware.
        manager.addLifecycleListener(new PlaybackLifecycleListener() {

            @Override
            public void contextCreated(long deviceHandle) {
                throw new NoClassDefFoundError("org.lwjgl.openal.AL10");
            }

            @Override
            public void contextDestroying() {
                throw new RuntimeException("addon bug");
            }
        });
        manager.addLifecycleListener(new PlaybackLifecycleListener() {

            @Override
            public void contextCreated(long deviceHandle) {
                secondRan.set(true);
            }
        });

        assertDoesNotThrow(thread::fireContextCreated);
        assertDoesNotThrow(thread::fireContextDestroying);
        assertTrue(secondRan.get(), "a well-behaved listener must still run after an earlier one threw");
    }

    @Test
    void throwingListenerDoesNotStarveLaterListenersOnSourceEvents() {
        PlaybackManager manager = new PlaybackManager();
        PlaybackThread thread = new PlaybackThread(manager, null, Config.HrtfMode.AUTO);
        RecordingListener second = new RecordingListener();

        manager.addLifecycleListener(new PlaybackLifecycleListener() {

            @Override
            public void sourceCreated(UUID sourceId, int sourceHandle) {
                throw new NoClassDefFoundError("org.lwjgl.openal.AL10");
            }

            @Override
            public void sourceDestroying(UUID sourceId, int sourceHandle) {
                throw new RuntimeException("addon bug");
            }
        });
        manager.addLifecycleListener(second);

        assertDoesNotThrow(() -> thread.fireSourceCreated(SOURCE_A, 5));
        assertDoesNotThrow(() -> thread.fireSourceDestroying(SOURCE_A, 5));

        assertEquals(
            Arrays.asList("sourceCreated:" + SOURCE_A + ":5", "sourceDestroying:" + SOURCE_A + ":5"),
            second.events,
            "a well-behaved listener must still see source events after an earlier one threw");
    }

    @Test
    void nullListenerRegistrationThrows() {
        PlaybackManager manager = new PlaybackManager();
        assertThrows(NullPointerException.class, () -> manager.addLifecycleListener(null));
    }
}
