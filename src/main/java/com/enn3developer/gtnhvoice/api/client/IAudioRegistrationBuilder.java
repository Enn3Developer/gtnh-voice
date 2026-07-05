package com.enn3developer.gtnhvoice.api.client;

import org.jetbrains.annotations.NotNull;

/**
 * Fluent, single-use assembler for one playback-side registration bundle, created only by
 * {@link IClientAudioApi#register}. Chain any mix of whole listeners, per-event callbacks and playback filters -
 * every method returns this builder, every method may be called repeatedly and ALL calls accumulate (two
 * {@code onAudioTick} callbacks both fire, in registration order) - then terminate with {@link #done()}, which
 * activates the bundle and returns the {@link IRegistration} handle that removes it as a whole.
 * <p>
 * Nothing is registered until {@link #done()}: an abandoned builder leaks nothing. An empty bundle is a caller
 * bug - {@code done()} without at least one listener, callback or filter throws {@link IllegalStateException}.
 * Single use is enforced: any call after {@code done()}, including a second {@code done()}, throws
 * {@link IllegalStateException}.
 * <p>
 * The whole listeners and per-event callbacks assemble into one listener per bundle, all sharing
 * {@link IAudioLifecycleListener}'s threading, pairing, reentrancy and ownership contract - read it before
 * hooking anything. See {@link IClientAudioApi#register} for the durability and mid-session-replay contract.
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
     * Terminal: activates the accumulated bundle durably and returns the handle that removes it. The builder is
     * dead afterwards - hold on to the handle, not the builder.
     *
     * @return the bundle's one {@link IRegistration} handle
     * @throws IllegalStateException if the bundle is empty, or if {@code done()} already ran
     */
    IRegistration done();
}
