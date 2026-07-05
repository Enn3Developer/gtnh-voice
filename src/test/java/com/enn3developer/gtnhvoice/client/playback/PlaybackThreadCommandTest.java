package com.enn3developer.gtnhvoice.client.playback;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.Config;

/**
 * Exercises {@link PlaybackThread}'s command-queue contracts that don't need an AL device: dead-thread
 * rejection in {@link PlaybackThread#enqueueCommand} and Throwable isolation in
 * {@link PlaybackThread#runCommandIsolated}. The thread is deliberately never started, so no OpenAL device is
 * opened and the context stays unbound - which also means the isolation path's AL-error drain (only taken with
 * a bound context) is not covered here; that branch is exercised only in-game.
 */
class PlaybackThreadCommandTest {

    private PlaybackThread newUnstartedThread() {
        return new PlaybackThread(new PlaybackManager(), null, Config.HrtfMode.AUTO);
    }

    @Test
    void enqueueCommandRejectsBeforeStart() {
        PlaybackThread thread = newUnstartedThread();
        AtomicBoolean ran = new AtomicBoolean();

        assertFalse(thread.enqueueCommand(() -> ran.set(true)));
        assertFalse(ran.get());
    }

    @Test
    void enqueueCommandRejectsAfterShutdown() {
        PlaybackThread thread = newUnstartedThread();
        thread.shutdown();

        assertFalse(thread.enqueueCommand(() -> {}));
    }

    @Test
    void runCommandIsolatedSwallowsErrorsNotJustExceptions() {
        PlaybackThread thread = newUnstartedThread();

        // The exact failure mode addon commands hit when their class is missing @Lwjgl3Aware.
        assertDoesNotThrow(
            () -> thread.runCommandIsolated(() -> { throw new NoClassDefFoundError("org.lwjgl.openal.AL10"); }));
        assertDoesNotThrow(() -> thread.runCommandIsolated(() -> { throw new RuntimeException("addon bug"); }));

        AtomicBoolean ran = new AtomicBoolean();
        thread.runCommandIsolated(() -> ran.set(true));
        assertTrue(ran.get(), "a well-behaved command must still run after earlier ones threw");
    }

    @Test
    void executorRejectsWhenPlaybackNotRunning() {
        PlaybackManager manager = new PlaybackManager();

        assertFalse(
            manager.audioThreadExecutor()
                .execute(() -> {}));
    }

    @Test
    void nullCommandThrowsRegardlessOfLifecycleState() {
        PlaybackThread thread = newUnstartedThread();
        assertThrows(NullPointerException.class, () -> thread.enqueueCommand(null), "dead thread must still NPE");

        PlaybackManager manager = new PlaybackManager();
        assertThrows(
            NullPointerException.class,
            () -> manager.audioThreadExecutor()
                .execute(null),
            "executor must NPE even with no playback thread to delegate to");
    }
}
