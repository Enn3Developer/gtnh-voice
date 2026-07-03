package com.enn3developer.gtnhvoice;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

import com.enn3developer.gtnhvoice.core.proto.data.audio.codec.opus.OpusMode;

public class Config {

    private static final String CATEGORY_VOICE = "voice";

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

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

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

        if (configuration.hasChanged()) {
            configuration.save();
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
