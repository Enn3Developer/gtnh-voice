package com.enn3developer.gtnhvoice.client.audio;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.capture.CaptureManager;
import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.client.source.VoiceSourceManager;

/**
 * Client audio-subsystem control API: live input/output device and HRTF-mode switching, on top of the
 * capture/playback hotswap machinery in {@link CaptureManager} and {@link PlaybackManager}. Every setter here
 * persists to {@link Config} immediately (so the choice survives a restart) and, if a voice session is currently
 * active, pushes it live to the running audio threads; if no session is active, the persisted value is simply
 * picked up the next time capture/playback starts.
 * <p>
 * Called by {@code GuiVoiceSettings} (the in-game settings screen).
 */
public final class AudioDeviceController {

    private static final AudioDeviceController INSTANCE = new AudioDeviceController();

    private volatile CaptureManager captureManager;

    public static AudioDeviceController getInstance() {
        return INSTANCE;
    }

    private AudioDeviceController() {}

    /**
     * Wires the shared {@link CaptureManager} in - it's long-lived (bound once at mod init, independent of any
     * particular voice session), unlike {@link PlaybackManager} which is looked up per-session via {@link
     * VoiceClientManager#getVoiceSourceManager()} on every call below.
     */
    public void bindCaptureManager(@NotNull CaptureManager captureManager) {
        this.captureManager = captureManager;
    }

    public List<String> listInputDevices() {
        return AudioDeviceUtil.listInputDevices();
    }

    public List<String> listOutputDevices() {
        return AudioDeviceUtil.listOutputDevices();
    }

    /**
     * Currently selected input device, or {@code null} for the system default.
     */
    public @Nullable String getInputDevice() {
        return Config.getInputDeviceOrNull();
    }

    /**
     * Currently selected output device, or {@code null} for the system default.
     */
    public @Nullable String getOutputDevice() {
        return Config.getOutputDeviceOrNull();
    }

    public Config.HrtfMode getHrtfMode() {
        return Config.getHrtfMode();
    }

    /**
     * Live input-device hotswap: stops the current capture device and opens the newly named one, without touching
     * the rest of the voice session. {@code deviceName} {@code null} selects the system default.
     */
    public void setInputDevice(@Nullable String deviceName) {
        Config.inputDevice = deviceName == null ? "" : deviceName;
        Config.save();

        CaptureManager manager = captureManager;
        if (manager != null) {
            manager.setInputDevice(deviceName);
        }

        GtnhVoice.LOG.info("[AudioDevice] Input device set to {}", deviceName == null ? "<default>" : deviceName);
    }

    /**
     * Live output-device hotswap: triggers the full ordered playback-context rebuild on the currently active
     * session's {@link PlaybackManager}, keeping the current HRTF mode. {@code deviceName} {@code null} selects the
     * system default.
     */
    public void setOutputDevice(@Nullable String deviceName) {
        Config.outputDevice = deviceName == null ? "" : deviceName;
        Config.save();

        PlaybackManager playbackManager = currentPlaybackManager();
        if (playbackManager != null) {
            playbackManager.rebuildOutput(deviceName, Config.getHrtfMode());
        }

        GtnhVoice.LOG.info("[AudioDevice] Output device set to {}", deviceName == null ? "<default>" : deviceName);
    }

    /**
     * Live HRTF-mode hotswap: triggers the full ordered playback-context rebuild on the currently active session's
     * {@link PlaybackManager}, keeping the current output device.
     */
    public void setHrtfMode(@NotNull Config.HrtfMode mode) {
        Config.hrtfMode = mode.name();
        Config.save();

        PlaybackManager playbackManager = currentPlaybackManager();
        if (playbackManager != null) {
            playbackManager.rebuildOutput(Config.getOutputDeviceOrNull(), mode);
        }

        GtnhVoice.LOG.info("[AudioDevice] HRTF mode set to {}", mode);
    }

    private @Nullable PlaybackManager currentPlaybackManager() {
        VoiceSourceManager sourceManager = VoiceClientManager.getInstance()
            .getVoiceSourceManager();
        return sourceManager == null ? null : sourceManager.getPlaybackManager();
    }
}
