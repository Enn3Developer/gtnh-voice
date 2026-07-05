package com.enn3developer.gtnhvoice.api.client;

/**
 * Handle for one registration bundle produced by a {@code register(...)...done()} chain - the only way to undo
 * a registration. {@link #unregister()} removes the whole bundle: its lifecycle listener stops receiving events
 * and its filters drop out of the processing chains (a callback already in flight on its worker thread may still
 * complete). Idempotent and callable from any thread. {@link #close()} delegates to {@link #unregister()} so a
 * handle works in try-with-resources, though most addons simply keep theirs for the game's lifetime - durable
 * registrations need no per-session cleanup.
 */
public interface IRegistration extends AutoCloseable {

    /** Removes the whole bundle this handle was returned for. Safe to call repeatedly, from any thread. */
    void unregister();

    /** Delegates to {@link #unregister()}; never throws. */
    @Override
    default void close() {
        unregister();
    }
}
