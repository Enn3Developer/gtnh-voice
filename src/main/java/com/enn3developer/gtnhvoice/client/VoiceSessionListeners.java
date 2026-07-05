package com.enn3developer.gtnhvoice.client;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;

/**
 * Registry and dispatcher for {@link VoiceSessionListener}s. Owned by {@link VoiceClientManager} for its
 * whole singleton lifetime, so registrations survive disconnect/reconnect cycles. CopyOnWrite for the same
 * reason as {@link CapturePcmFilterChain}: dispatch snapshots freely while registration happens from
 * arbitrary threads and churns rarely.
 * <p>
 * Started/stopping pairing is self-enforcing rather than call-site discipline: {@link #fireSessionStopping()}
 * silently no-ops unless a matching {@link #fireSessionStarted()} preceded it, which makes
 * {@code VoiceClientManager.closeUdp()}'s unconditional-call contexts (fresh connect, repeated disconnects)
 * safe by construction. The pairing flag is guarded by the manager's monitor - the fire methods are only
 * called from its {@code synchronized} session control methods (tests call them single-threaded).
 */
final class VoiceSessionListeners {

    private static final long LISTENER_ERROR_LOG_INTERVAL_MILLIS = 1_000L;

    private final List<VoiceSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong lastListenerErrorLogMillis = new AtomicLong();

    /** Whether a started event has fired without its paired stopping yet - see the class javadoc. */
    private boolean sessionEventActive;

    /** Registers a session listener - see {@link VoiceSessionListener} for the threading and failure contract. */
    void add(VoiceSessionListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
    }

    /** Unregisters a previously added listener; no-op if it was never registered. */
    void remove(VoiceSessionListener listener) {
        listeners.remove(listener);
    }

    /**
     * Dispatches {@link VoiceSessionListener#sessionStarted()} to every listener and arms the pairing flag.
     * A second started without an intervening stopping is a session-control bug: it WARNs and does not
     * re-fire, so listeners never observe two consecutive starts.
     */
    void fireSessionStarted() {
        if (sessionEventActive) {
            GtnhVoice.LOG.warn("[Voice] sessionStarted fired twice without a sessionStopping in between; ignored");
            return;
        }
        sessionEventActive = true;

        for (VoiceSessionListener listener : listeners) {
            dispatch(listener, "sessionStarted", listener::sessionStarted);
        }
    }

    /**
     * Dispatches {@link VoiceSessionListener#sessionStopping()} to every listener, but only if a started
     * event is pending - otherwise a silent no-op, by design: teardown runs unconditionally in contexts
     * where no session was ever up.
     */
    void fireSessionStopping() {
        if (!sessionEventActive) return;
        sessionEventActive = false;

        for (VoiceSessionListener listener : listeners) {
            dispatch(listener, "sessionStopping", listener::sessionStopping);
        }
    }

    private void dispatch(VoiceSessionListener listener, String event, Runnable callback) {
        try {
            callback.run();
        } catch (Throwable t) {
            if (!LogThrottle.shouldLog(lastListenerErrorLogMillis, LISTENER_ERROR_LOG_INTERVAL_MILLIS)) return;
            GtnhVoice.LOG.error("[Voice] Session listener {} threw from {}; skipped", listener, event, t);
        }
    }
}
