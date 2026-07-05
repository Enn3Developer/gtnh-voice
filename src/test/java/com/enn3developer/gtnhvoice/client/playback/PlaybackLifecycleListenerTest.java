package com.enn3developer.gtnhvoice.client.playback;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.Config;

/**
 * Exercises the {@link PlaybackLifecycleListener} registry and dispatch contracts that don't need an AL device:
 * registration/removal on {@link PlaybackManager}, the {@link PlaybackThread} fire helpers invoking listeners, and
 * per-listener Throwable isolation. As in {@link PlaybackThreadCommandTest}, the thread is deliberately never
 * started, so no OpenAL device is opened - real context creation/destruction (and thus the actual fire sites in
 * run()/performRebuild) can only be exercised in-game.
 */
class PlaybackLifecycleListenerTest {

    /** Listener that just counts invocations of both callbacks. */
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
    void nullListenerRegistrationThrows() {
        PlaybackManager manager = new PlaybackManager();
        assertThrows(NullPointerException.class, () -> manager.addLifecycleListener(null));
    }
}
