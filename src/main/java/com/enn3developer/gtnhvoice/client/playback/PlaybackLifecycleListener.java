package com.enn3developer.gtnhvoice.client.playback;

/**
 * Internal hook points for the playback thread's OpenAL context lifecycle - the seam a future public addon API
 * (EFX effect slots/filters, which must be created after a context exists and freed before it dies) will build on.
 * Listeners are registered on {@link PlaybackManager} so registrations survive start/stop cycles and rebuilds;
 * dispatch happens in {@link PlaybackThread}.
 * <p>
 * Threading contract: both callbacks run on the playback thread with the live context bound and current, so
 * implementations may call {@code AL10}/{@code ALC10} directly - which also means an implementor class that
 * touches {@code org.lwjgl} needs lwjgl3ify's {@code @Lwjgl3Aware}, exactly like queued commands do. Each callback
 * is dispatched individually isolated (catch-Throwable, throttled error log): one broken listener can neither kill
 * the pump loop nor starve other listeners.
 * <p>
 * Pairing contract: {@code contextCreated}/{@code contextDestroying} strictly alternate. Every context that becomes
 * the live output is announced exactly once via {@link #contextCreated}, and gets exactly one
 * {@link #contextDestroying} before it is destroyed. Transient rebuild contexts that fail before ever becoming the
 * live output are never announced at all.
 * <p>
 * All methods are default no-ops so future events (e.g. per-source lifecycle) can be added without touching
 * existing implementors.
 */
interface PlaybackLifecycleListener {

    /**
     * A context just became the live output - fired at startup and after every successful rebuild, including the
     * default-device fallback. Runs with the new context bound and current, before any AL sources exist on it.
     *
     * @param deviceHandle the ALC device the live context was created on, for extension checks like
     *                     {@code alcIsExtensionPresent(deviceHandle, "ALC_EXT_EFX")}
     */
    default void contextCreated(long deviceHandle) {}

    /**
     * The live context is about to be torn down - fired before ANY AL teardown begins: the context is still bound
     * and current and every AL source still exists, so listeners can free their AL objects against a fully valid
     * context. Reserved ordering: a later task will fire per-source destruction events before this one (still
     * ahead of any actual AL teardown), mirroring the real teardown order of sources-then-context.
     */
    default void contextDestroying() {}
}
