package com.enn3developer.gtnhvoice.api.client;

/**
 * Handle for one registration bundle produced by a {@code register(...)...done()} chain - the only way to undo
 * a registration. {@link #unregister()} removes the whole bundle: its lifecycle listener stops receiving events
 * and its filters drop out of the processing chains (a callback already in flight on its worker thread may still
 * complete). Idempotent and callable from any thread. {@link #close()} delegates to {@link #unregister()} so a
 * handle works in try-with-resources, though most addons simply keep theirs for the game's lifetime - durable
 * registrations need no per-session cleanup.
 * <p>
 * Beyond removal, the handle also carries the bundle's runtime filter gate: {@link #setFilterEnabled(boolean)} /
 * {@link #isFilterEnabled()} flip the whole bundle's PCM filters between live and passthrough without unwiring
 * anything. See those methods for the durability and threading contract; the gate is orthogonal to
 * {@link #unregister()} - a live bundle can be gated off, a gated-off bundle still unregisters normally.
 */
public interface IRegistration extends AutoCloseable {

    /** Removes the whole bundle this handle was returned for. Safe to call repeatedly, from any thread. */
    void unregister();

    /**
     * Enables or disables this bundle's PCM filters at runtime. When disabled, every filter in the bundle - raw
     * {@code filter(...)}/{@code playbackFilter(...)} registrations AND {@code chain(...)}/{@code playbackChain(...)}
     * recipes alike - short-circuits to passthrough (the frame flows through untouched) BEFORE any stage runs; the
     * bundle's lifecycle listeners keep firing and its {@code auxiliarySends} request stays counted, so toggling is
     * purely a filter gate and never triggers an OpenAL context rebuild. The initial state is the bundle's
     * {@code initiallyEnabled(...)} value (default enabled).
     * <p>
     * Backed by a single volatile flag per bundle: callable from ANY thread (typically the client game thread
     * from an input handler), read on the decode/capture thread, so a flip takes effect from the next frame that
     * thread processes with no tearing. Calling this on an already-unregistered handle is a harmless no-op,
     * consistent with {@link #unregister()}'s idempotence.
     *
     * @param enabled {@code true} to run the bundle's filters, {@code false} to pass audio through them untouched
     */
    void setFilterEnabled(boolean enabled);

    /**
     * Whether this bundle's PCM filters are currently enabled - the flag {@link #setFilterEnabled(boolean)} sets,
     * seeded from {@code initiallyEnabled(...)} (default {@code true}). Callable from any thread. Returns
     * {@code false} for an already-unregistered handle, consistent with {@link #unregister()}'s idempotence.
     *
     * @return {@code true} if the bundle's filters run, {@code false} if they are gated to passthrough (or the
     *         handle is unregistered)
     */
    boolean isFilterEnabled();

    /** Delegates to {@link #unregister()}; never throws. */
    @Override
    default void close() {
        unregister();
    }
}
