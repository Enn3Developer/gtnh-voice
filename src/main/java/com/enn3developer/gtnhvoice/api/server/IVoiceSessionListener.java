package com.enn3developer.gtnhvoice.api.server;

import org.jetbrains.annotations.NotNull;

/**
 * Observes voice sessions starting and ending on the server - the place to set up per-player voice state such as
 * group memberships, instead of guessing from login/logout events that know nothing about voice. Add one with
 * {@link GtnhVoiceApi#addSessionListener}; both callbacks default to no-ops, so implement only what you need.
 *
 * <pre>{@code
 * GtnhVoiceApi.addSessionListener(new IVoiceSessionListener() {
 *
 *     public void sessionStarted(IVoiceSession session) {
 *         UUID player = session.getPlayerUuid();
 *         if (holdsRadio(player)) GtnhVoiceApi.groupManager().join(player, radioGroup);
 *     }
 * });
 * }</pre>
 * <p>
 * Threading: every callback runs on the server thread, from the voice server's tick queue - never on the Netty or
 * session-reaper threads - so callbacks may touch world and player state and change group membership. Events are
 * delivered at the end of a server tick, so they trail what happened by at most one tick.
 * <p>
 * Ordering, per player: every {@code sessionStarted} is followed by exactly one {@code sessionEnded} for that
 * session; an old session's {@code sessionEnded} always comes before the next session's {@code sessionStarted};
 * when a session ends by {@link SessionEndReason#LOGOUT} or {@link SessionEndReason#TIMED_OUT}, the player's
 * memberships are wiped before its {@code sessionEnded} - including any made in its own {@code sessionStarted}.
 * Once a session's {@code sessionStarted} has been delivered, nothing from an older session can wipe that player's
 * memberships, so a {@code join} made in {@code sessionStarted} survives until that session itself ends. Events of
 * different players carry no ordering promise relative to each other.
 * <p>
 * Late registration: a listener added while sessions are live first receives {@code sessionStarted} for each of
 * them, in the order they started, on the server thread and before any later event - it observes the same world
 * an early listener would have.
 * <p>
 * Durability: listeners survive voice server restarts (a singleplayer world reload stops and restarts it), so add
 * them once, e.g. in {@code FMLInitializationEvent}; when the voice server stops, every live session ends with
 * {@link SessionEndReason#SERVER_STOPPED}. Remove a listener with the returned {@link IListenerRegistration}.
 * <p>
 * Failure isolation: a callback that throws is logged (throttled) and skipped; other listeners and the voice
 * server carry on.
 */
public interface IVoiceSessionListener {

    /**
     * {@code session} started: the server accepted the player's voice handshake. This usually happens before the
     * client sent its first UDP datagram, so {@link IVoiceSession#hasUdpAddress()} is typically still
     * {@code false} - audio routed to the player is dropped until it turns {@code true}.
     */
    default void sessionStarted(@NotNull IVoiceSession session) {}

    /** {@code session} ended for {@code reason} - see {@link SessionEndReason} for what happened to memberships. */
    default void sessionEnded(@NotNull IVoiceSession session, @NotNull SessionEndReason reason) {}
}
