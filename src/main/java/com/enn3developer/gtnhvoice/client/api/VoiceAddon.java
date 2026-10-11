package com.enn3developer.gtnhvoice.client.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.api.client.IAddonActivation;
import com.enn3developer.gtnhvoice.api.client.IAddonSourceBuilder;
import com.enn3developer.gtnhvoice.api.client.IAudioRegistrationBuilder;
import com.enn3developer.gtnhvoice.api.client.ICaptureRegistrationBuilder;
import com.enn3developer.gtnhvoice.api.client.ISourceMetadata;
import com.enn3developer.gtnhvoice.api.client.IVoiceAddon;
import com.enn3developer.gtnhvoice.api.server.ActivationIds;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.client.source.VoiceSourceManager;

/**
 * The one {@code IVoiceAddon} implementation: {@link #audio()}/{@link #capture()} hand out registration
 * builders that store into {@link ClientApiBackend} attributed to this addon's name, while the live queries
 * reach the current session's {@code PlaybackManager} through {@link VoiceClientManager} - a null session
 * manager IS the documented no-session case (false/empty). Activations are created on first request and kept for
 * the client lifetime in a concurrent map, so {@link #activation} is a race-free get-or-create; everything else is
 * immutable.
 */
final class VoiceAddon implements IVoiceAddon {

    private final ClientApiBackend backend;
    private final String name;
    private final @Nullable String description;
    private final ConcurrentMap<String, IAddonActivation> activations = new ConcurrentHashMap<>();

    VoiceAddon(ClientApiBackend backend, String name, @Nullable String description) {
        this.backend = backend;
        this.name = name;
        this.description = description;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    @Override
    public IAudioRegistrationBuilder audio() {
        return new AudioRegistrationBuilder(backend, name);
    }

    @Override
    public ICaptureRegistrationBuilder capture() {
        return new CaptureRegistrationBuilder(backend, name);
    }

    @Override
    public IAddonSourceBuilder source() {
        return new AddonSourceBuilder(name);
    }

    @Override
    public IAddonActivation activation(@NotNull String name) {
        // Derive first: it is also the name validation (null, blank, ':'), so a bad name never reaches the map.
        UUID id = ActivationIds.of(this.name, name);
        return activations.computeIfAbsent(name, n -> new AddonActivation(id, backend.keyedActivations()));
    }

    @Override
    public boolean runOnAudioThread(@NotNull Runnable command) {
        // Validate before the no-session check - a null command must fail identically whether or not a
        // session is up, per the interface contract.
        Objects.requireNonNull(command, "command");

        PlaybackManager playback = livePlaybackManager();
        return playback != null && playback.runOnAudioThread(command);
    }

    @Override
    public Optional<ISourceMetadata> sourceMetadata(@NotNull UUID sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");

        PlaybackManager playback = livePlaybackManager();
        if (playback == null) return Optional.empty();
        return playback.sourceMetadataFor(sourceId);
    }

    /** The current session's playback manager, or {@code null} with no session - shared with {@link AddonSource}. */
    static @Nullable PlaybackManager livePlaybackManager() {
        VoiceSourceManager sourceManager = VoiceClientManager.getInstance()
            .getVoiceSourceManager();
        if (sourceManager == null) return null;
        return sourceManager.getPlaybackManager();
    }
}
