package com.enn3developer.gtnhvoice.api.client;

import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/**
 * The playback-side addon API: durable lifecycle/filter registration bundles via {@link #register}, plus live
 * queries against the running voice session. Obtain the instance via {@link GtnhVoiceClientApi#audio()}.
 */
public interface IClientAudioApi {

    /**
     * Opens a fluent registration bundle attributed to {@code addonName} - the EXCLUSIVE way to hook playback:
     * chain whole lifecycle listeners, per-event callbacks and playback PCM filters on the returned builder,
     * then call {@code done()} to activate the bundle and receive the {@link IRegistration} handle that removes
     * it as a whole.
     * <p>
     * Registrations are DURABLE: register once (e.g. at FML init) and the bundle survives voice session
     * disconnects and reconnects without any re-registration. Registering mid-session is fully supported: the
     * current state is replayed to the new listener on the audio thread - {@code contextCreated} for the live
     * context, then {@code sourceCreated} for every live source - so a late registrant observes the same world
     * an early one would have.
     * <p>
     * {@code addonName} is attribution, not identity: it names the addon in error logs (and future per-addon
     * throttling), and multiple bundles may share one name.
     *
     * @param addonName the registering addon's name, for attribution; non-null and non-blank
     * @return a single-use builder - see {@link IAudioRegistrationBuilder} for the chaining rules
     * @throws NullPointerException     if {@code addonName} is null
     * @throws IllegalArgumentException if {@code addonName} is blank
     */
    IAudioRegistrationBuilder register(@NotNull String addonName);

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
