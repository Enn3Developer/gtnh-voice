package com.enn3developer.gtnhvoice.client;

import com.enn3developer.gtnhvoice.client.source.VoiceSourceManager;

/**
 * Internal observer of the client voice session lifecycle - the seam a future addon-API dispatch layer will
 * use to re-bridge durable addon registrations onto the per-session managers ({@link VoiceSourceManager},
 * its PlaybackManager, {@link CaptureSendWorker}), all of which are torn down and recreated on every
 * connect. Listeners are registered on the {@link VoiceSessionListeners} registry owned by
 * {@link VoiceClientManager}, so registrations survive disconnect/reconnect cycles.
 * <p>
 * Threading contract: both callbacks run on whatever thread drove the session transition (typically the FML
 * network-handler thread), while {@link VoiceClientManager}'s monitor is held - the session control methods
 * are {@code synchronized}. Implementations must be fast and non-blocking, and must NOT call session-control
 * methods ({@code onDisconnected} etc.) from inside the callback - that would re-enter the monitor and
 * corrupt the transition in progress.
 * <p>
 * Failure isolation: a listener that throws is logged (throttled) and skipped - one broken listener must not
 * starve the others or abort the session transition.
 */
interface VoiceSessionListener {

    /**
     * Fires after a session is fully up: UDP connected, session state CONNECTED, {@link VoiceSourceManager}
     * started, capture worker started. During the callback {@link VoiceClientManager#getVoiceSourceManager()}
     * returns the fresh per-session manager - the seam the dispatch layer bridges registrations through.
     * Never fires for a failed connect (the exception path that sets the session DISABLED).
     */
    default void sessionStarted() {}

    /**
     * Fires before ANY teardown begins: the per-session managers are still alive and queryable, so listeners
     * can detach cleanly. Strictly paired with {@link #sessionStarted()}: the two alternate, and the teardown
     * paths that run when no session was ever up (fresh connect, repeated disconnects) produce no event -
     * enforced by {@link VoiceSessionListeners}, not by call-site discipline.
     */
    default void sessionStopping() {}
}
