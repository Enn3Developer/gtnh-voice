package com.enn3developer.gtnhvoice.api.client;

import java.util.UUID;

/**
 * Addon hook points for the voice output's OpenAL context and per-source lifecycle: create global AL state (EFX
 * effect slots, effects) after a context exists and free it before the context dies, attach per-source AL state
 * (EFX filters, aux sends) to individual voice sources, and drive continuous updates from {@link #audioTick()}.
 * Registered exclusively through an {@link IClientAudioApi#register} bundle - either whole via
 * {@code lifecycle(...)} or per-event via the {@code on*} builder methods, which assemble into this same
 * contract. All methods are default no-ops so future events can be added without breaking existing implementors.
 * <p>
 * Threading: every callback runs on the mod's dedicated audio thread with the mod's OpenAL context bound and
 * current, so implementations may call {@code AL10}/{@code ALC10}/EFX functions directly. Under lwjgl3ify
 * (GTNH's LWJGL3 runtime) any implementor class that touches {@code org.lwjgl} classes needs lwjgl3ify's
 * {@code @Lwjgl3Aware} annotation, or it fails with {@code NoClassDefFoundError} at runtime.
 * <p>
 * Pairing and ordering: {@link #contextCreated} and {@link #contextDestroying} strictly alternate. Every
 * {@link #sourceCreated} is bracketed by the created/destroying pair of the context it lives on, and gets exactly
 * one {@link #sourceDestroying} before its AL source is deleted - whether the source dies individually (its
 * speaker disconnected) or with the whole context. On a context teardown, {@code sourceDestroying} fires for
 * every live source, then {@code contextDestroying}, all before any actual AL destruction begins - callbacks
 * always run against fully valid handles. {@link #audioTick} fires only between a context pair, and only while
 * at least one source exists.
 * <p>
 * What fires no event: a source reset at the end of a speech segment. The AL source survives a reset, so
 * listener state attached to the handle stays valid - do not expect create/destroy churn per speech segment.
 * After an output-device/HRTF rebuild, AL sources reappear lazily (on each speaker's next audio packet) with
 * fresh handles on the new context, announced through {@code sourceCreated} naturally.
 * <p>
 * Reentrancy: do NOT call any gtnh-voice API method from inside a callback - you are already on the audio
 * thread, inside its dispatch, and direct AL calls are the intended tool here. From every other thread it is the
 * opposite: never call AL directly, marshal through {@link IClientAudioApi#runOnAudioThread}.
 * <p>
 * Ownership: {@code AL_GAIN}, {@code AL_POSITION} and the distance-attenuation setup on voice sources belong to
 * gtnh-voice, which rewrites them continuously - addons express themselves through EFX aux sends and direct
 * filters on the source, never by fighting over those properties.
 * <p>
 * Failure isolation: a callback that throws is logged (throttled, attributed to the registration's addon name)
 * and skipped - it can neither kill voice playback nor starve other registrations.
 */
public interface IAudioLifecycleListener {

    /**
     * A context just became the live output - fired when a voice session's playback starts and after every
     * successful output-device/HRTF rebuild. Runs with the new context bound and current, before any AL sources
     * exist on it - the moment to create global AL state such as EFX effect slots.
     *
     * @param deviceHandle the ALC device handle the live context was created on, for extension checks like
     *                     {@code alcIsExtensionPresent(deviceHandle, "ALC_EXT_EFX")}
     */
    default void contextCreated(long deviceHandle) {}

    /**
     * The live context is about to be torn down - fired before ANY AL teardown begins: the context is still
     * bound and current, so global AL state can be freed against a fully valid context. By the time this fires,
     * {@link #sourceDestroying} has already fired for every live source on this context (also still ahead of any
     * actual AL teardown) - the events mirror the real sources-then-context destruction order, one step ahead
     * of it.
     */
    default void contextDestroying() {}

    /**
     * A positioned AL source now exists for {@code sourceId} - fired after the source is fully configured, so
     * per-source AL state (EFX filters, aux sends) can be attached to a complete source. Only sources that
     * actually came into existence are announced; a failed allocation fires nothing.
     *
     * @param sourceId     the voice source this AL source plays (the speaking player's UUID)
     * @param sourceHandle the AL source name, valid until the matching {@link #sourceDestroying}
     */
    default void sourceCreated(UUID sourceId, int sourceHandle) {}

    /**
     * {@code sourceId}'s AL source is about to be deleted - the handle and the context are still fully valid, so
     * per-source AL state can be detached and freed. Fired both when a source dies individually and, for every
     * live source, at the start of a context teardown (before {@link #contextDestroying}).
     *
     * @param sourceId     the voice source whose AL source is dying
     * @param sourceHandle the AL source name announced by the matching {@link #sourceCreated}
     */
    default void sourceDestroying(UUID sourceId, int sourceHandle) {}

    /**
     * Periodic heartbeat for continuous per-source AL updates (EFX send gains, filter parameters) - fired once
     * per audio pump iteration while at least one AL source exists, and completely silent while none do (sources
     * appearing and vanishing are already announced via {@link #sourceCreated}/{@link #sourceDestroying}, so the
     * silence loses no information). The nominal interval is ~5ms, but NOTHING is guaranteed - under load it
     * stretches arbitrarily. Self-throttle expensive work (world raycasts and the like) to your own budget and
     * treat this as an upper bound on update frequency, not a schedule.
     * <p>
     * Time budget: this runs INSIDE the audio pump loop - a slow callback delays voice playback for everyone.
     * Keep per-tick work well under the nominal interval.
     */
    default void audioTick() {}
}
