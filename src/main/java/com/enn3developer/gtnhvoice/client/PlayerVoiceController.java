package com.enn3developer.gtnhvoice.client;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.client.source.VoiceSourceManager;

/**
 * Control-API entry point for the Players GUI, mirroring {@code AudioDeviceController}: every setter updates the
 * persistent {@link PlayerVoiceSettings} source of truth and, if a voice session is currently active, live-pushes
 * the change to the running {@link PlaybackManager}. Mute needs no live push - the UDP receive path
 * ({@code VoiceClientManager#onUdpPacket}) reads {@link PlayerVoiceSettings#isMuted} directly on the next packet.
 */
public final class PlayerVoiceController {

    private static final PlayerVoiceController INSTANCE = new PlayerVoiceController();

    public static PlayerVoiceController getInstance() {
        return INSTANCE;
    }

    private PlayerVoiceController() {}

    public float getVolume(UUID uuid) {
        return PlayerVoiceSettings.getInstance()
            .getVolume(uuid);
    }

    public boolean isMuted(UUID uuid) {
        return PlayerVoiceSettings.getInstance()
            .isMuted(uuid);
    }

    /**
     * Live volume hotswap: persists the new value and, if {@code uuid} currently has an active AL source, posts a
     * gain update to the playback thread so the change is audible immediately.
     */
    public void setVolume(UUID uuid, float volume) {
        PlayerVoiceSettings.getInstance()
            .setVolume(uuid, volume);

        PlaybackManager playbackManager = currentPlaybackManager();
        if (playbackManager != null) {
            playbackManager.setGain(
                uuid,
                PlayerVoiceSettings.getInstance()
                    .getVolume(uuid));
        }
    }

    public void setMuted(UUID uuid, boolean value) {
        PlayerVoiceSettings.getInstance()
            .setMuted(uuid, value);
    }

    private @Nullable PlaybackManager currentPlaybackManager() {
        VoiceSourceManager sourceManager = VoiceClientManager.getInstance()
            .getVoiceSourceManager();
        return sourceManager == null ? null : sourceManager.getPlaybackManager();
    }
}
