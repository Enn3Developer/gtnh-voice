package com.enn3developer.gtnhvoice.client.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.api.client.IAudioRegistrationBuilder;
import com.enn3developer.gtnhvoice.api.client.IClientAudioApi;
import com.enn3developer.gtnhvoice.api.client.ISourceMetadata;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.client.source.VoiceSourceManager;

/**
 * The one {@code IClientAudioApi} implementation: registration hands out builders that store into
 * {@link ClientApiBackend}, while the live queries reach the current session's {@code PlaybackManager} through
 * {@link VoiceClientManager} - a null session manager IS the documented no-session case (false/empty).
 */
final class ClientAudioApi implements IClientAudioApi {

    private final ClientApiBackend backend;

    ClientAudioApi(ClientApiBackend backend) {
        this.backend = backend;
    }

    @Override
    public IAudioRegistrationBuilder register(@NotNull String addonName) {
        return new AudioRegistrationBuilder(backend, ClientApiBackend.validateAddonName(addonName));
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
