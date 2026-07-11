package com.enn3developer.gtnhvoice.api.client;

import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/**
 * The handle a registered addon holds for its whole lifetime, returned once by {@link IAddonBuilder#register()}
 * - the EXCLUSIVE gateway to the client voice pipeline. Everything an addon does flows through it: opening
 * playback/capture registration bundles ({@link #audio()}, {@link #capture()}), audio-thread marshalling
 * ({@link #runOnAudioThread}) and live source queries ({@link #sourceMetadata}). There is deliberately no way to
 * reach any of these without registering first.
 * <p>
 * The handle is thread-safe and durable for the client lifetime: it survives voice session disconnects and
 * reconnects, and there is no way to unregister an addon - individual bundles are removed through their own
 * {@link IRegistration} handles.
 */
public interface IVoiceAddon {

    /** The unique name this addon registered under - see {@link GtnhVoiceClient#addon(String)}. */
    String name();

    /** The human-readable description declared at registration, or empty when none was given. */
    Optional<String> description();

    /**
     * Opens a fluent playback-side registration bundle attributed to this addon - the EXCLUSIVE way to hook
     * playback: chain whole lifecycle listeners, per-event callbacks and playback PCM filters on the returned
     * builder, then call {@code done()} to activate the bundle and receive the {@link IRegistration} handle
     * that removes it as a whole. Every call opens a fresh, independent, single-use builder; one addon may hold
     * any number of live bundles.
     * <p>
     * Bundles are DURABLE: activate once (e.g. at FML init) and the bundle survives voice session disconnects
     * and reconnects without any re-registration. Activating mid-session is fully supported: the current state
     * is replayed to the new listener on the audio thread - {@code contextCreated} for the live context, then
     * {@code sourceCreated} for every live source - so a late registrant observes the same world an early one
     * would have.
     *
     * @return a single-use builder - see {@link IAudioRegistrationBuilder} for the chaining rules
     */
    IAudioRegistrationBuilder audio();

    /**
     * Opens a fluent capture-side registration bundle attributed to this addon - the EXCLUSIVE way to hook the
     * capture path: chain capture PCM filters on the returned builder, then call {@code done()} to activate the
     * bundle and receive the {@link IRegistration} handle that removes it as a whole. Every call opens a fresh,
     * independent, single-use builder; one addon may hold any number of live bundles.
     * <p>
     * Bundles are DURABLE: activate once (e.g. at FML init) and the bundle survives voice session disconnects
     * and reconnects without any re-registration. Activating mid-session is fully supported and takes effect
     * from the next captured mic frame.
     *
     * @return a single-use builder - see {@link ICaptureRegistrationBuilder} for the chaining rules
     */
    ICaptureRegistrationBuilder capture();

    /**
     * Submits {@code command} to run on the mod's audio thread with its OpenAL context bound and current,
     * serialized with every other AL call that thread makes - the ONLY sanctioned way to touch this mod's AL
     * state from outside a lifecycle callback. Like every audio-thread visitor, the command's class needs
     * lwjgl3ify's {@code @Lwjgl3Aware} annotation if it touches {@code org.lwjgl}.
     * <p>
     * Returns {@code false} when no voice session/playback is running - the command is then dropped, never
     * queued. {@code true} means ACCEPTED, not guaranteed: playback tearing down concurrently with the
     * submission may discard an accepted command unrun. A command that throws is isolated and logged; it cannot
     * break playback.
     *
     * @param command what to run on the audio thread
     * @return whether the command was accepted
     * @throws NullPointerException if {@code command} is null - a caller bug, reported identically whether or
     *                              not playback is running
     */
    boolean runOnAudioThread(@NotNull Runnable command);

    /**
     * The spatial snapshot of {@code sourceId}, or empty when the id is unknown (that player never spoke, or
     * their source has been torn down) or no voice session is running. Callable from any thread - typically from
     * an audio tick. See {@link ISourceMetadata} for the consistency and freshness contract.
     *
     * @param sourceId the voice source to query (the speaking player's UUID)
     * @return a detached point-in-time snapshot, or empty
     */
    Optional<ISourceMetadata> sourceMetadata(@NotNull UUID sourceId);
}
