package com.enn3developer.gtnhvoice.api.server;

/**
 * Why a voice session ended, as passed to {@link IVoiceSessionListener#sessionEnded}. Each reason also says what
 * happened to the player's group memberships before the event was delivered.
 */
public enum SessionEndReason {

    /** The player logged out. Their group memberships were wiped before {@code sessionEnded} was delivered. */
    LOGOUT,

    /**
     * The session carried no voice traffic for the session timeout (3 minutes) and was reaped - the player may
     * still be online with a broken voice client. Their group memberships were wiped, on the server thread, before
     * {@code sessionEnded} was delivered.
     */
    TIMED_OUT,

    /**
     * The same player completed a newer voice handshake (a reconnect, or a relog that overtook the old
     * connection's logout); the newer session's {@code sessionStarted} follows immediately. Group memberships are
     * kept - they carry over to the newer session.
     */
    REPLACED,

    /**
     * The voice server stopped (server shutdown, or a singleplayer world unloading). The whole group registry -
     * every membership and every registered group - was cleared before {@code sessionEnded} was delivered.
     */
    SERVER_STOPPED
}
