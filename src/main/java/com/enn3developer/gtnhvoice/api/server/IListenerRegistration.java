package com.enn3developer.gtnhvoice.api.server;

/**
 * Handle for one listener added through {@link GtnhVoiceApi} - the only way to remove it. Listeners are durable
 * (they survive voice server restarts), so most addons keep the handle for the game's lifetime; {@link #close()}
 * delegates to {@link #unregister()} so the handle also works in try-with-resources.
 */
public interface IListenerRegistration extends AutoCloseable {

    /**
     * Removes the listener. Called on the server thread - for example from inside one of its own callbacks - the
     * listener receives nothing more; called from another thread, a callback the server thread is already
     * delivering may still complete. Idempotent and callable from any thread.
     */
    void unregister();

    /** Delegates to {@link #unregister()}; never throws. */
    @Override
    default void close() {
        unregister();
    }
}
