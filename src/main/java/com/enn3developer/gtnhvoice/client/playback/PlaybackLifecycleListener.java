package com.enn3developer.gtnhvoice.client.playback;

import java.util.UUID;

/**
 * Internal hook points for the playback thread's OpenAL context and per-source lifecycle - the seam a future public
 * addon API (EFX effect slots/filters, which must be created after a context exists and freed before it dies, plus
 * per-source filters/aux sends attached to individual AL sources) will build on. Listeners are registered on
 * {@link PlaybackManager} so registrations survive start/stop cycles and rebuilds; dispatch happens in
 * {@link PlaybackThread}.
 * <p>
 * Threading contract: all callbacks run on the playback thread with the live context bound and current, so
 * implementations may call {@code AL10}/{@code ALC10} directly - which also means an implementor class that
 * touches {@code org.lwjgl} needs lwjgl3ify's {@code @Lwjgl3Aware}, exactly like queued commands do. Each callback
 * is dispatched individually isolated (catch-Throwable, throttled error log): one broken listener can neither kill
 * the pump loop nor starve other listeners.
 * <p>
 * Pairing contract: {@code contextCreated}/{@code contextDestroying} strictly alternate. Every context that becomes
 * the live output is announced exactly once via {@link #contextCreated}, and gets exactly one
 * {@link #contextDestroying} before it is destroyed. Transient rebuild contexts that fail before ever becoming the
 * live output are never announced at all. Likewise every {@link #sourceCreated} is bracketed by the
 * created/destroying pair of the context it lives on, and gets exactly one {@link #sourceDestroying} before its AL
 * source is deleted - whether it dies individually (speaker disconnect) or with the whole context (rebuild or
 * shutdown teardown).
 * <p>
 * No event fires for a source <em>reset</em> (speech-segment inactivity, {@code PlaybackThread#resetSourceChannel}):
 * the AL source survives a reset, so any listener state attached to the handle stays valid - do not expect
 * create/destroy churn per speech segment. After a device/HRTF rebuild, AL sources are recreated lazily (next audio
 * packet for each speaker), so listeners see fresh {@link #sourceCreated} events with new handles on the new context
 * naturally - there is no re-fire machinery.
 * <p>
 * All methods are default no-ops so future events can be added without touching existing implementors.
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
     * context. Ordering guarantee: by the time this fires, {@link #sourceDestroying} has already fired for every
     * live AL source on this context (still ahead of any actual AL teardown) - events mirror the real
     * sources-then-context destruction order, one step ahead of it.
     */
    default void contextDestroying() {}

    /**
     * A positioned AL source now exists for {@code sourceId} - fired after the source is fully configured and
     * registered, so listeners can attach per-source AL state (EFX filters, aux sends) to a complete source. Never
     * fired for a source whose allocation failed partway; only sources that actually came into existence are
     * announced.
     *
     * @param sourceId     the voice source this AL source plays
     * @param sourceHandle the AL source name, valid until the matching {@link #sourceDestroying}
     */
    default void sourceCreated(UUID sourceId, int sourceHandle) {}

    /**
     * {@code sourceId}'s AL source is about to be deleted - the handle and the context are still fully valid, so
     * listeners can detach and free their per-source AL state. Fired both when a source dies individually and, for
     * every live source, at the start of a context teardown (before {@link #contextDestroying}).
     *
     * @param sourceId     the voice source whose AL source is dying
     * @param sourceHandle the AL source name announced by the matching {@link #sourceCreated}
     */
    default void sourceDestroying(UUID sourceId, int sourceHandle) {}
}
