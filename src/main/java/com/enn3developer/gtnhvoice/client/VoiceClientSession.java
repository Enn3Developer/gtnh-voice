package com.enn3developer.gtnhvoice.client;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;

/**
 * Immutable snapshot of client-side voice session state, queryable for a future GUI/HUD.
 */
public final class VoiceClientSession {

    public enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISABLED
    }

    public static final VoiceClientSession DISCONNECTED = new VoiceClientSession(
        State.DISCONNECTED,
        null,
        null,
        null,
        0,
        (byte) 0,
        0,
        0);

    private final State state;
    private final String disabledReason;
    private final UUID sessionId;
    private final AesEncryption encryption;
    private final int distance;
    private final byte opusMode;
    private final int frameSize;
    private final int sampleRate;

    public VoiceClientSession(State state, @Nullable String disabledReason, @Nullable UUID sessionId,
        @Nullable AesEncryption encryption, int distance, byte opusMode, int frameSize, int sampleRate) {
        this.state = state;
        this.disabledReason = disabledReason;
        this.sessionId = sessionId;
        this.encryption = encryption;
        this.distance = distance;
        this.opusMode = opusMode;
        this.frameSize = frameSize;
        this.sampleRate = sampleRate;
    }

    public State getState() {
        return state;
    }

    public @Nullable String getDisabledReason() {
        return disabledReason;
    }

    public @Nullable UUID getSessionId() {
        return sessionId;
    }

    public @Nullable AesEncryption getEncryption() {
        return encryption;
    }

    public int getDistance() {
        return distance;
    }

    public byte getOpusMode() {
        return opusMode;
    }

    public int getFrameSize() {
        return frameSize;
    }

    public int getSampleRate() {
        return sampleRate;
    }
}
