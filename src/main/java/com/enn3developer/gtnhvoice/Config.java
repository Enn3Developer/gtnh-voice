package com.enn3developer.gtnhvoice;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

import com.enn3developer.gtnhvoice.core.proto.data.audio.codec.opus.OpusMode;

public class Config {

    private static final String CATEGORY_VOICE = "voice";

    public static String greeting = "Hello World";

    // Server-authoritative voice session config, sent to clients in ServerHelloPacket.
    public static int udpPort = 25566;
    public static int distance = 48;
    public static int maxDistance = 128;
    public static String opusMode = OpusMode.VOIP.name();
    public static int frameSize = 960;
    public static int sampleRate = 48_000;

    // DEBUG: lets the client simulate a protocol-version mismatch to exercise the ServerReject path.
    public static boolean debugForceProtocolMismatch = false;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        greeting = configuration.getString("greeting", Configuration.CATEGORY_GENERAL, greeting, "How shall I greet?");

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
}
