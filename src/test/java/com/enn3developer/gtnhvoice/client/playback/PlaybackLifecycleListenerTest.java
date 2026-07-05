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

/**
 * Exercises the {@link PlaybackLifecycleListener} registry and dispatch contracts that don't need an AL device:
 * registration/removal on {@link PlaybackManager}, the {@link LifecycleEventDispatcher} fire helpers invoking
 * listeners (context and per-source), per-listener Throwable isolation, {@code fireContextTeardown}'s
 * sources-before-context ordering via a seeded {@link SourceChannelPool}, and the audioTick call-site guard
 * ({@code if (!pool.isEmpty()) dispatcher.fireAudioTick()}, exactly as {@code PlaybackThread.run()} phrases it)
 * via the same seeding. The dispatcher is constructed standalone - no {@link PlaybackThread} and no OpenAL device
 * - so the actual fire sites in run()/performRebuild/createSourceChannel/destroySourceChannel can only be
 * exercised in-game.
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

    /** Standalone dispatcher over {@code manager}'s registry, with a no-op post-failure hook (no AL to drain). */
    private static LifecycleEventDispatcher newDispatcher(PlaybackManager manager) {
        return new LifecycleEventDispatcher(manager, new IsolatedRunner(() -> {}));
    }

    private static SourceChannelPool newPool(PlaybackManager manager, LifecycleEventDispatcher dispatcher) {
        return new SourceChannelPool(manager, dispatcher);
    }

    private static SourceChannelPool.SourceChannel channelWithHandle(int alSource) {
        return new SourceChannelPool.SourceChannel(
            alSource,
            new int[0],
            new ArrayDeque<>(),
            new LinkedBlockingQueue<>());
    }

    @Test
    void fireHelpersInvokeRegisteredListener() {
        PlaybackManager manager = new PlaybackManager();
        LifecycleEventDispatcher dispatcher = newDispatcher(manager);
        CountingListener listener = new CountingListener();
        manager.addLifecycleListener(listener);

        dispatcher.fireContextCreated(0L);
        dispatcher.fireContextDestroying();

        assertEquals(1, listener.created.get());
        assertEquals(1, listener.destroying.get());
    }

    @Test
    void sourceFireHelpersPassSourceIdAndHandle() {
        PlaybackManager manager = new PlaybackManager();
        LifecycleEventDispatcher dispatcher = newDispatcher(manager);
        RecordingListener listener = new RecordingListener();
        manager.addLifecycleListener(listener);

        dispatcher.fireSourceCreated(SOURCE_A, 42);
        dispatcher.fireSourceDestroying(SOURCE_A, 42);

        assertEquals(Arrays.asList("sourceCreated:" + SOURCE_A + ":42", "sourceDestroying:" + SOURCE_A + ":42"),
            listener.events);
    }

    @Test
    void contextOnlyListenerIgnoresSourceEvents() {
        // Default-method compatibility: CountingListener predates the per-source events and must neither break
        // compilation (proved by this file compiling) nor react to them at runtime.
        PlaybackManager manager = new PlaybackManager();
        LifecycleEventDispatcher dispatcher = newDispatcher(manager);
        CountingListener listener = new CountingListener();
        manager.addLifecycleListener(listener);

        assertDoesNotThrow(() -> dispatcher.fireSourceCreated(SOURCE_A, 7));
        assertDoesNotThrow(() -> dispatcher.fireSourceDestroying(SOURCE_A, 7));

        assertEquals(0, listener.created.get());
        assertEquals(0, listener.destroying.get());
    }

    @Test
    void contextTeardownAnnouncesAllSourcesBeforeContext() {
        PlaybackManager manager = new PlaybackManager();
        LifecycleEventDispatcher dispatcher = newDispatcher(manager);
        SourceChannelPool pool = newPool(manager, dispatcher);
        RecordingListener listener = new RecordingListener();
        manager.addLifecycleListener(listener);

        // Safe off-thread only because no playback thread exists - see the class javadoc.
        pool.sourceChannels.put(SOURCE_A, channelWithHandle(11));
        pool.sourceChannels.put(SOURCE_B, channelWithHandle(22));

        dispatcher.fireContextTeardown(pool.channelsView());

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
        LifecycleEventDispatcher dispatcher = newDispatcher(manager);
        CountingListener listener = new CountingListener();
        manager.addLifecycleListener(listener);
        manager.removeLifecycleListener(listener);

        dispatcher.fireContextCreated(0L);
        dispatcher.fireContextDestroying();

        assertEquals(0, listener.created.get());
        assertEquals(0, listener.destroying.get());
    }

    @Test
    void registrationsSurviveAcrossDispatcherInstances() {
        // The registry lives on the manager, not the dispatcher - a listener registered once must still be seen
        // by a later dispatcher instance, mirroring what a start()/stop() cycle does (each PlaybackThread builds
        // its own dispatcher).
        PlaybackManager manager = new PlaybackManager();
        CountingListener listener = new CountingListener();
        manager.addLifecycleListener(listener);

        newDispatcher(manager).fireContextCreated(0L);
        newDispatcher(manager).fireContextCreated(0L);

        assertEquals(2, listener.created.get());
    }

    @Test
    void throwingListenerDoesNotStarveLaterListeners() {
        PlaybackManager manager = new PlaybackManager();
        LifecycleEventDispatcher dispatcher = newDispatcher(manager);
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

        assertDoesNotThrow(() -> dispatcher.fireContextCreated(0L));
        assertDoesNotThrow(dispatcher::fireContextDestroying);
        assertTrue(secondRan.get(), "a well-behaved listener must still run after an earlier one threw");
    }

    @Test
    void throwingListenerDoesNotStarveLaterListenersOnSourceEvents() {
        PlaybackManager manager = new PlaybackManager();
        LifecycleEventDispatcher dispatcher = newDispatcher(manager);
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

        assertDoesNotThrow(() -> dispatcher.fireSourceCreated(SOURCE_A, 5));
        assertDoesNotThrow(() -> dispatcher.fireSourceDestroying(SOURCE_A, 5));

        assertEquals(
            Arrays.asList("sourceCreated:" + SOURCE_A + ":5", "sourceDestroying:" + SOURCE_A + ":5"),
            second.events,
            "a well-behaved listener must still see source events after an earlier one threw");
    }

    @Test
    void audioTickWithNoSourcesFiresNothing() {
        PlaybackManager manager = new PlaybackManager();
        LifecycleEventDispatcher dispatcher = newDispatcher(manager);
        SourceChannelPool pool = newPool(manager, dispatcher);
        AtomicInteger ticks = new AtomicInteger();
        manager.addLifecycleListener(new PlaybackLifecycleListener() {

            @Override
            public void audioTick() {
                ticks.incrementAndGet();
            }
        });

        // The guard lives at the call site now - this is the exact condition PlaybackThread.run() evaluates.
        if (!pool.isEmpty()) dispatcher.fireAudioTick();

        assertEquals(0, ticks.get(), "audioTick must stay silent while no AL source exists");
    }

    @Test
    void audioTickWithLiveSourceInvokesListener() {
        PlaybackManager manager = new PlaybackManager();
        LifecycleEventDispatcher dispatcher = newDispatcher(manager);
        SourceChannelPool pool = newPool(manager, dispatcher);
        AtomicInteger ticks = new AtomicInteger();
        manager.addLifecycleListener(new PlaybackLifecycleListener() {

            @Override
            public void audioTick() {
                ticks.incrementAndGet();
            }
        });
        // Default-method compatibility: the context-only listener must coexist with tick dispatch untouched.
        CountingListener contextOnly = new CountingListener();
        manager.addLifecycleListener(contextOnly);

        // Safe off-thread only because no playback thread exists - see the class javadoc.
        pool.sourceChannels.put(SOURCE_A, channelWithHandle(11));

        // The guard lives at the call site now - this is the exact condition PlaybackThread.run() evaluates.
        if (!pool.isEmpty()) dispatcher.fireAudioTick();

        assertEquals(1, ticks.get());
        assertEquals(0, contextOnly.created.get());
        assertEquals(0, contextOnly.destroying.get());
    }

    @Test
    void throwingListenerDoesNotStarveLaterListenersOnAudioTick() {
        PlaybackManager manager = new PlaybackManager();
        LifecycleEventDispatcher dispatcher = newDispatcher(manager);
        SourceChannelPool pool = newPool(manager, dispatcher);
        AtomicBoolean secondTicked = new AtomicBoolean();

        manager.addLifecycleListener(new PlaybackLifecycleListener() {

            @Override
            public void audioTick() {
                throw new NoClassDefFoundError("org.lwjgl.openal.AL10");
            }
        });
        manager.addLifecycleListener(new PlaybackLifecycleListener() {

            @Override
            public void audioTick() {
                secondTicked.set(true);
            }
        });

        pool.sourceChannels.put(SOURCE_A, channelWithHandle(11));

        assertDoesNotThrow(() -> { if (!pool.isEmpty()) dispatcher.fireAudioTick(); });
        assertTrue(secondTicked.get(), "a well-behaved listener must still tick after an earlier one threw");
    }

    @Test
    void nullListenerRegistrationThrows() {
        PlaybackManager manager = new PlaybackManager();
        assertThrows(NullPointerException.class, () -> manager.addLifecycleListener(null));
    }
}
