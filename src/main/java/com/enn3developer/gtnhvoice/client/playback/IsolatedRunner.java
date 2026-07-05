package com.enn3developer.gtnhvoice.client.playback;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;

/**
 * The isolation wrapper behind both {@link PlaybackThread#runCommandIsolated queued commands} and
 * {@link LifecycleEventDispatcher listener dispatch}: runs a task, catching any {@link Throwable} it throws -
 * deliberately including errors, since an addon task whose class is missing lwjgl3ify's {@code @Lwjgl3Aware} dies
 * with {@code NoClassDefFoundError}, and that must not tear down playback. Failures are logged at error level,
 * throttled to one per {@value #ERROR_LOG_INTERVAL_MILLIS}ms, then the injected post-failure hook runs
 * ({@link PlaybackThread} injects {@link OutputDeviceContext#drainAlErrorAfterFailedCommand}, so a half-executed
 * task's dangling AL error isn't misattributed to the playback thread's next internal
 * {@link AlDebug#checkAlError}).
 * <p>
 * Exactly one instance exists per {@link PlaybackThread}, shared by the command drain and the dispatcher -
 * commands and listeners share one error-log throttle, deliberately. All state is thread-confined to the playback
 * thread: {@link #run} only ever executes there (unit tests aside), so the {@link AtomicLong} is inherited from
 * {@link LogThrottle}'s API, not a sign of cross-thread use.
 */
final class IsolatedRunner {

    private static final long ERROR_LOG_INTERVAL_MILLIS = 1_000L;

    private final AtomicLong lastErrorLogMillis = new AtomicLong();
    private final Runnable postFailureHook;

    IsolatedRunner(Runnable postFailureHook) {
        this.postFailureHook = Objects.requireNonNull(postFailureHook, "postFailureHook");
    }

    /** Runs {@code task} isolated as per the class contract, naming {@code what} in the throttled failure log. */
    void run(Runnable task, String what) {
        try {
            task.run();
        } catch (Throwable t) {
            if (LogThrottle.shouldLog(lastErrorLogMillis, ERROR_LOG_INTERVAL_MILLIS)) {
                GtnhVoice.LOG.error("[Playback] {} threw on the playback thread", what, t);
            }
            postFailureHook.run();
        }
    }
}
