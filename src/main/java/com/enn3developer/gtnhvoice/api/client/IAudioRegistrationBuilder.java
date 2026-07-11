package com.enn3developer.gtnhvoice.api.client;

import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;

/**
 * Fluent, single-use assembler for one playback-side registration bundle, created only by
 * {@link IVoiceAddon#audio()}. Chain any mix of whole listeners, per-event callbacks and playback filters -
 * every method returns this builder, every method may be called repeatedly and ALL calls accumulate (two
 * {@code onAudioTick} callbacks both fire, in registration order) - then terminate with {@link #done()}, which
 * activates the bundle and returns the {@link IRegistration} handle that removes it as a whole. The lone
 * exception to "all calls accumulate" is {@link #auxiliarySends}, a per-bundle scalar that keeps its largest
 * argument rather than stacking.
 * <p>
 * Nothing is registered until {@link #done()}: an abandoned builder leaks nothing. An empty bundle is a caller
 * bug - {@code done()} without at least one listener, callback, filter or auxiliary-sends request throws
 * {@link IllegalStateException}.
 * Single use is enforced: any call after {@code done()}, including a second {@code done()}, throws
 * {@link IllegalStateException}.
 * <p>
 * The whole listeners and per-event callbacks assemble into one listener per bundle, all sharing
 * {@link IAudioLifecycleListener}'s threading, pairing, reentrancy and ownership contract - read it before
 * hooking anything. See {@link IVoiceAddon#audio()} for the durability and mid-session-replay contract.
 */
public interface IAudioRegistrationBuilder {

    /**
     * Adds a whole lifecycle listener to the bundle - the right shape when one object cares about several
     * events; for a single event, the {@code on*} methods spare you the class.
     */
    IAudioRegistrationBuilder lifecycle(@NotNull IAudioLifecycleListener listener);

    /** Adds a callback for {@link IAudioLifecycleListener#contextCreated} to the bundle. */
    IAudioRegistrationBuilder onContextCreated(@NotNull IContextCreated callback);

    /** Adds a callback for {@link IAudioLifecycleListener#contextDestroying} to the bundle. */
    IAudioRegistrationBuilder onContextDestroying(@NotNull Runnable callback);

    /** Adds a callback for {@link IAudioLifecycleListener#sourceCreated} to the bundle. */
    IAudioRegistrationBuilder onSourceCreated(@NotNull ISourceEvent callback);

    /** Adds a callback for {@link IAudioLifecycleListener#sourceDestroying} to the bundle. */
    IAudioRegistrationBuilder onSourceDestroying(@NotNull ISourceEvent callback);

    /** Adds a callback for {@link IAudioLifecycleListener#audioTick} to the bundle. */
    IAudioRegistrationBuilder onAudioTick(@NotNull Runnable callback);

    /**
     * Adds a PCM filter on incoming voice to the bundle - see {@link IPlaybackPcmFilter} for the threading,
     * format and failure contract. Filters across all bundles run in registration order.
     */
    IAudioRegistrationBuilder playbackFilter(@NotNull IPlaybackPcmFilter filter);

    /**
     * Adds a {@link IPcmChain} recipe as one playback filter - the fluent alternative to hand-writing an
     * {@link IPlaybackPcmFilter}. The {@code spec} lambda is invoked IMMEDIATELY to record the chain's stages;
     * the compiled filter then sits at the exact seam {@link #playbackFilter} occupies. The mod instantiates a
     * fresh stateful pipeline PER VOICE SOURCE off the source lifecycle, so the recipe's biquads and other
     * stateful stages get independent per-speaker delay lines automatically - satisfying
     * {@link IPlaybackPcmFilter}'s concurrency contract with no keying-by-{@code sourceId} on your part.
     * Deliberately NOT an overload of {@code playbackFilter(...)}: an implicitly-typed lambda is ambiguous
     * between the two, so this carries its own name.
     * <p>
     * Like {@link #playbackFilter}, repeated calls ACCUMULATE - two chains both run, in registration order,
     * interleaved with any raw filters by call order - and a non-empty chain counts as a filter for
     * {@link #done()}'s empty-bundle check.
     *
     * @param spec records the chain's stages onto the supplied {@link IPcmChain}; non-null
     * @throws NullPointerException     if {@code spec} is null
     * @throws IllegalArgumentException if {@code spec} records no stages, or if any stage's arguments are invalid
     *                                  (both surface here, at the call site, not later on the audio path)
     */
    IAudioRegistrationBuilder playbackChain(@NotNull Consumer<IPcmChain> spec);

    /**
     * Sets whether this bundle's filters start enabled - see {@link IRegistration#setFilterEnabled(boolean)} for
     * what the gate does. Unlike the accumulating listener/filter methods this is a per-bundle SCALAR: repeated
     * calls do NOT stack, the LAST call wins (like {@link #auxiliarySends}). The gate covers only PCM filters -
     * lifecycle listeners and the {@code auxiliarySends} request are unaffected. Not calling it leaves the bundle
     * enabled.
     *
     * @param enabled the bundle's initial filter-gate state; default {@code true}
     */
    IAudioRegistrationBuilder initiallyEnabled(boolean enabled);

    /**
     * Declares that this bundle needs at least {@code sends} auxiliary sends per voice source - the number of
     * EFX effect slots (reverb, echo, ...) a single source can feed at once. OpenAL Soft provisions two per
     * source by default; an addon routing a source through more slots than that (e.g. a multi-slot reverb bus)
     * must ask for the count it needs here. The host aggregates the maximum across all live registrations and,
     * when that maximum is non-zero and the device advertises {@code ALC_EXT_EFX}, passes it as
     * {@code ALC_MAX_AUXILIARY_SENDS} when it creates the OpenAL context. Not calling this method means the
     * bundle imposes no requirement and the host keeps OpenAL Soft's default. Calling it more than once keeps
     * the largest value, not the last.
     * <p>
     * This is a REQUEST, not a guarantee. The ALC implementation is free to grant fewer sends than asked (the
     * EFX specification explicitly permits it), and extra sends carry a per-source mixing cost, which is why the
     * host only widens the count when an addon declares it needs to. Query what was actually granted from
     * {@link IAudioLifecycleListener#contextCreated} - you run on the audio thread with the device handle in
     * hand, so {@code alcGetIntegerv(deviceHandle, EXTEfx.ALC_MAX_AUXILIARY_SENDS, ...)} is available - and
     * degrade gracefully (fold effects onto fewer slots) if you got less than you asked for.
     * <p>
     * Requesting more than the context was already built with on a live session triggers a context rebuild
     * (the same teardown/recreate as a device or HRTF change), so a mid-session registrant is provisioned too;
     * registering before the session's context exists - the usual FML-init case - is simply picked up at
     * creation with no rebuild. Dropping a registration never shrinks a live context; the reduced requirement
     * applies at the next natural rebuild.
     *
     * @param sends the minimum auxiliary sends per source this bundle needs, in {@code [1, 8]}
     * @throws IllegalArgumentException if {@code sends} is outside {@code [1, 8]}
     */
    IAudioRegistrationBuilder auxiliarySends(int sends);

    /**
     * Terminal: activates the accumulated bundle durably and returns the handle that removes it. The builder is
     * dead afterwards - hold on to the handle, not the builder.
     *
     * @return the bundle's one {@link IRegistration} handle
     * @throws IllegalStateException if the bundle is empty, or if {@code done()} already ran
     */
    IRegistration done();
}
