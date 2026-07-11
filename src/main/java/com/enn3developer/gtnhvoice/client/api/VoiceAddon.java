package com.enn3developer.gtnhvoice.client.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.api.client.IAudioRegistrationBuilder;
import com.enn3developer.gtnhvoice.api.client.ICaptureRegistrationBuilder;
import com.enn3developer.gtnhvoice.api.client.ISourceMetadata;
import com.enn3developer.gtnhvoice.api.client.IVoiceAddon;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.client.source.VoiceSourceManager;

/**
 * The one {@code IVoiceAddon} implementation: {@link #audio()}/{@link #capture()} hand out registration
 * builders that store into {@link ClientApiBackend} attributed to this addon's name, while the live queries
 * reach the current session's {@code PlaybackManager} through {@link VoiceClientManager} - a null session
 * manager IS the documented no-session case (false/empty). Immutable, so trivially thread-safe.
 */
final class VoiceAddon implements IVoiceAddon {

    private final ClientApiBackend backend;
    private final String name;
    private final @Nullable String description;

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

    private static @Nullable PlaybackManager livePlaybackManager() {
        VoiceSourceManager sourceManager = VoiceClientManager.getInstance()
            .getVoiceSourceManager();
        if (sourceManager == null) return null;
        return sourceManager.getPlaybackManager();
    }
}
