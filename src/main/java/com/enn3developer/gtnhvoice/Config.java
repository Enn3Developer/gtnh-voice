package com.enn3developer.gtnhvoice;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

import com.enn3developer.gtnhvoice.client.PlayerVoiceSettings;
import com.enn3developer.gtnhvoice.core.proto.data.audio.codec.opus.OpusMode;

public class Config {

    private static final String CATEGORY_VOICE = "voice";

    // Kept around so runtime setters (see #save) can persist a single field without re-reading every
    // property in synchronizeConfiguration - only ever set once, from synchronizeConfiguration itself.
    private static Configuration configuration;

    // Server-authoritative voice session config, sent to clients in ServerHelloPacket.
    public static int udpPort = 25566;
    public static int distance = 48;
    public static int maxDistance = 128;
    public static String opusMode = OpusMode.VOIP.name();
    public static int frameSize = 960;
    public static int sampleRate = 48_000;

    // DEBUG: lets the client simulate a protocol-version mismatch to exercise the ServerReject path.
    public static boolean debugForceProtocolMismatch = false;

    // Client-side transmission gating: which frames actually get Opus-encoded and sent. Not
    // server-authoritative - each client decides for itself when it's "speaking".
    public enum ActivationMode {
        VOICE_ACTIVATION,
        PUSH_TO_TALK
    }

    public static String activationMode = ActivationMode.VOICE_ACTIVATION.name();
    public static double vaThresholdDb = -40.0;
    public static int vaHangoverMs = 250;

    // Client-side capture filtering: whether to apply RNNoise noise suppression to captured mic
    // audio before Opus encoding. Only ever an optional acceleration - if the native RNNoise
    // library isn't available on this platform, voice keeps working without denoising regardless
    // of this setting (see NoiseSuppressionFilterSupplier).
    public static boolean denoiseEnabled = true;

    // Client-side who's-talking HUD overlay. No position/scale customization in this version - just an on/off
    // switch.
    public static boolean hudEnabled = true;

    // Client-side audio device selection + HRTF, hot-swappable at runtime via AudioDeviceController (driven by the
    // in-game settings GUI). Empty string means "system default" for both device fields, matching what
    // AudioDeviceController hands to the ALC layer as {@code null}.
    public enum HrtfMode {
        AUTO,
        ON,
        OFF
    }

    public static String inputDevice = "";
    public static String outputDevice = "";
    public static String hrtfMode = HrtfMode.AUTO.name();

    // Client-side voice loudness controls, both live-tunable from the settings GUI: outputVolume scales every
    // incoming voice source at once (OpenAL listener gain, 0-100%), micGain scales the captured mic signal
    // before denoise/filters/VA so the whole pipeline sees the amplified level (0-200%, 100 = untouched).
    public static int outputVolume = 100;
    public static int micGain = 100;

    // Client-side per-player volume/mute overrides of OTHER players, set via the in-game Players screen.
    // Runtime source of truth lives in PlayerVoiceSettings (a concurrent map/set, not a static field here like
    // everything else in this class) since it's read from the UDP receive path and written from the GUI far more
    // often than this config file is touched; this class only loads it in and exports it back out.
    private static final String PROPERTY_PLAYER_OVERRIDES = "playerOverrides";

    public static void synchronizeConfiguration(File configFile) {
        configuration = new Configuration(configFile);

        udpPort = configuration.getInt(
            "udpPort",
            CATEGORY_VOICE,
            udpPort,
            1,
            65535,
            "UDP port the server binds for voice sessions. Default 25566 (MC server default port + 1).");
        distance = configuration.getInt(
            "distance",
            CATEGORY_VOICE,
            distance,
            1,
            512,
            "Proximity voice distance in blocks. Sent to clients; also used server-side for audio routing.");
        maxDistance = configuration.getInt(
            "maxDistance",
            CATEGORY_VOICE,
            maxDistance,
            1,
            1024,
            "Hard server-side proximity cutoff in blocks, independent of 'distance'. Audio routing uses min(distance, maxDistance) as the effective range, so this caps how far voice can ever travel even if 'distance' is misconfigured high.");
        opusMode = configuration
            .getString("opusMode", CATEGORY_VOICE, opusMode, "Opus encoder mode: VOIP, AUDIO, or RESTRICTED_LOWDELAY.");
        frameSize = configuration
            .getInt("frameSize", CATEGORY_VOICE, frameSize, 120, 5760, "Opus frame size in samples per channel.");
        sampleRate = configuration
            .getInt("sampleRate", CATEGORY_VOICE, sampleRate, 8000, 48_000, "Opus sample rate in Hz.");
        debugForceProtocolMismatch = configuration.getBoolean(
            "debugForceProtocolMismatch",
            CATEGORY_VOICE,
            debugForceProtocolMismatch,
            "DEBUG: client claims protocolVersion+1 in its ClientHello to test the ServerReject/incompatible-version path. Do not enable in normal play.");
        activationMode = configuration.getString(
            "activationMode",
            CATEGORY_VOICE,
            activationMode,
            "Client-side mic transmission gating: VOICE_ACTIVATION (transmit above a level threshold) or PUSH_TO_TALK (transmit only while the bound key is held). Client-only, takes effect on next voice connection.");
        vaThresholdDb = configuration.getFloat(
            "vaThresholdDb",
            CATEGORY_VOICE,
            (float) vaThresholdDb,
            -60F,
            0F,
            "VOICE_ACTIVATION mode only: frame RMS level (dB, -60=quiet..0=loudest) above which the mic starts transmitting. Raise if background noise triggers transmission; lower if normal speech doesn't.");
        vaHangoverMs = configuration.getInt(
            "vaHangoverMs",
            CATEGORY_VOICE,
            vaHangoverMs,
            0,
            2000,
            "VOICE_ACTIVATION mode only: milliseconds to keep transmitting after the level drops below vaThresholdDb, so brief pauses between words don't cut off speech.");
        denoiseEnabled = configuration.getBoolean(
            "denoiseEnabled",
            CATEGORY_VOICE,
            denoiseEnabled,
            "Apply RNNoise noise suppression to captured mic audio before sending. Only takes effect if the native RNNoise library loads for your platform - voice keeps working without denoising either way. Set to false to disable even when available.");
        hudEnabled = configuration.getBoolean(
            "hudEnabled",
            CATEGORY_VOICE,
            hudEnabled,
            "Show the who's-talking HUD overlay (top-left list of currently speaking players). Set to false to disable it entirely.");
        inputDevice = configuration.getString(
            "inputDevice",
            CATEGORY_VOICE,
            inputDevice,
            "Preferred OpenAL capture (microphone) device name, or empty for the system default. Set via in-game audio controls; applied at voice-session startup and hot-swappable while connected.");
        outputDevice = configuration.getString(
            "outputDevice",
            CATEGORY_VOICE,
            outputDevice,
            "Preferred OpenAL playback (speaker) device name, or empty for the system default. Set via in-game audio controls; applied at voice-session startup and hot-swappable while connected.");
        hrtfMode = configuration.getString(
            "hrtfMode",
            CATEGORY_VOICE,
            hrtfMode,
            "HRTF mode for 3D-positioned voice playback: AUTO (driver default), ON, or OFF. Set via in-game audio controls; applied at voice-session startup and hot-swappable while connected.");
        outputVolume = configuration.getInt(
            "outputVolume",
            CATEGORY_VOICE,
            outputVolume,
            0,
            100,
            "Master volume for all incoming voice audio, in percent (0=muted, 100=full). Set via the in-game settings GUI; applied live.");
        micGain = configuration.getInt(
            "micGain",
            CATEGORY_VOICE,
            micGain,
            0,
            200,
            "Gain applied to captured mic audio before denoising and transmission, in percent (100=untouched, 200=doubled). Set via the in-game settings GUI; applied live.");
        String[] playerOverrides = configuration.getStringList(
            PROPERTY_PLAYER_OVERRIDES,
            CATEGORY_VOICE,
            new String[0],
            "Per-player volume/mute overrides for other players, set via the in-game Players screen. Client-side only, no effect on the server. One entry per overridden player: '<uuid>,<volume 0.0-2.0>,<muted true/false>'. Players at default volume (1.0) and unmuted are pruned automatically - do not edit by hand.");
        PlayerVoiceSettings.getInstance()
            .loadOverrides(playerOverrides);

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    /**
     * Persists every client-editable live setting to disk immediately, without re-synchronizing the
     * server-authoritative properties above. Used by
     * {@link com.enn3developer.gtnhvoice.client.audio.AudioDeviceController}
     * (device/HRTF picks) and the settings GUI (activation mode, VA threshold/hangover, HUD, denoise) so a change
     * survives a restart right away rather than only on the next {@link #synchronizeConfiguration}.
     * <p>
     * Each property is re-fetched via the same value-type overload used to originally create it in
     * {@link #synchronizeConfiguration} (e.g. {@code vaThresholdDb} was created as a STRING property by
     * {@code Configuration.getFloat}) - fetching it back via a mismatched-type overload (e.g. the {@code double}
     * overload) makes {@code Configuration} think the property is being redefined with a new type and resets it to
     * its default value.
     */
    public static void save() {
        if (configuration == null) return;

        configuration.get(CATEGORY_VOICE, "activationMode", ActivationMode.VOICE_ACTIVATION.name())
            .set(activationMode);
        configuration.get(CATEGORY_VOICE, "vaThresholdDb", Double.toString(-40.0))
            .set(vaThresholdDb);
        configuration.get(CATEGORY_VOICE, "vaHangoverMs", 250)
            .set(vaHangoverMs);
        configuration.get(CATEGORY_VOICE, "denoiseEnabled", true)
            .set(denoiseEnabled);
        configuration.get(CATEGORY_VOICE, "hudEnabled", true)
            .set(hudEnabled);
        configuration.get(CATEGORY_VOICE, "inputDevice", "")
            .set(inputDevice);
        configuration.get(CATEGORY_VOICE, "outputDevice", "")
            .set(outputDevice);
        configuration.get(CATEGORY_VOICE, "hrtfMode", HrtfMode.AUTO.name())
            .set(hrtfMode);
        configuration.get(CATEGORY_VOICE, "outputVolume", 100)
            .set(outputVolume);
        configuration.get(CATEGORY_VOICE, "micGain", 100)
            .set(micGain);
        configuration.get(CATEGORY_VOICE, PROPERTY_PLAYER_OVERRIDES, new String[0])
            .set(
                PlayerVoiceSettings.getInstance()
                    .exportOverrides());
        configuration.save();
    }

    /**
     * {@link #inputDevice} normalized to the {@code null == default} convention the ALC layer (and {@link
     * com.enn3developer.gtnhvoice.client.capture.CaptureManager}) expects.
     */
    public static String getInputDeviceOrNull() {
        return inputDevice == null || inputDevice.isEmpty() ? null : inputDevice;
    }

    /**
     * {@link #outputDevice} normalized to the {@code null == default} convention the ALC layer (and {@link
     * com.enn3developer.gtnhvoice.client.playback.PlaybackManager}) expects.
     */
    public static String getOutputDeviceOrNull() {
        return outputDevice == null || outputDevice.isEmpty() ? null : outputDevice;
    }

    public static HrtfMode getHrtfMode() {
        try {
            return HrtfMode.valueOf(hrtfMode);
        } catch (IllegalArgumentException e) {
            GtnhVoice.LOG.warn("Invalid voice.hrtfMode '{}' in config, falling back to AUTO", hrtfMode);
            return HrtfMode.AUTO;
        }
    }

    public static OpusMode getOpusMode() {
        try {
            return OpusMode.valueOf(opusMode);
        } catch (IllegalArgumentException e) {
            GtnhVoice.LOG.warn("Invalid voice.opusMode '{}' in config, falling back to VOIP", opusMode);
            return OpusMode.VOIP;
        }
    }

    public static ActivationMode getActivationMode() {
        try {
            return ActivationMode.valueOf(activationMode);
        } catch (IllegalArgumentException e) {
            GtnhVoice.LOG
                .warn("Invalid voice.activationMode '{}' in config, falling back to VOICE_ACTIVATION", activationMode);
            return ActivationMode.VOICE_ACTIVATION;
        }
    }
}
