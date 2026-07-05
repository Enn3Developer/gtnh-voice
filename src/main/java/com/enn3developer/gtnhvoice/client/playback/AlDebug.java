package com.enn3developer.gtnhvoice.client.playback;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;

import com.enn3developer.gtnhvoice.GtnhVoice;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Static AL/ALC error-checking and pretty-printing helpers shared by the playback collaborators. Stateless; the
 * error-state calls ({@link #checkAlError}, {@link #checkAlcError}) must only run on the playback thread with its
 * context bound, exactly like every other AL call.
 */
@Lwjgl3Aware
final class AlDebug {

    private AlDebug() {}

    static boolean checkAlError(String context) {
        int error = AL10.alGetError();
        if (error == AL10.AL_NO_ERROR) return true;

        GtnhVoice.LOG.error("[Playback] AL error after {}: {}", context, alErrorToString(error));
        return false;
    }

    static boolean checkAlcError(long device, String context) {
        int error = ALC10.alcGetError(device);
        if (error == ALC10.ALC_NO_ERROR) return true;

        GtnhVoice.LOG.error("[Playback] ALC error after {}: {}", context, error);
        return false;
    }

    static String alErrorToString(int error) {
        return switch (error) {
            case AL10.AL_INVALID_NAME -> "AL_INVALID_NAME";
            case AL10.AL_INVALID_ENUM -> "AL_INVALID_ENUM";
            case AL10.AL_INVALID_VALUE -> "AL_INVALID_VALUE";
            case AL10.AL_INVALID_OPERATION -> "AL_INVALID_OPERATION";
            case AL10.AL_OUT_OF_MEMORY -> "AL_OUT_OF_MEMORY";
            default -> "unknown error code " + error;
        };
    }

    static String alSourceStateToString(int state) {
        return switch (state) {
            case AL10.AL_PLAYING -> "PLAYING";
            case AL10.AL_PAUSED -> "PAUSED";
            case AL10.AL_STOPPED -> "STOPPED";
            case AL10.AL_INITIAL -> "INITIAL";
            default -> "unknown state " + state;
        };
    }
}
