package com.enn3developer.gtnhvoice.client.source;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.client.slice.SimpleJitterBuffer;
import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.audio.codec.opus.JavaOpusDecoder;

/**
 * One remote speaker's full receive pipeline: its own Opus decoder (Opus is stateful - never shared across
 * sourceIds), its own {@link SimpleJitterBuffer}, and its own positioned AL source (owned by the shared
 * {@link PlaybackManager}, keyed by {@link #sourceId}).
 * <p>
 * Created lazily by {@link VoiceSourceManager} on the first {@code SourceAudioPacket} seen for a given sourceId,
 * which stays stable for the speaker's whole session. Two distinct lifecycle events, both driven by the manager:
 * {@link #segmentActive} on a short inactivity timeout (speaker paused, still connected - keeps this object but
 * clears its decode/jitter/AL state so the next segment starts clean) and {@link #destroy()} on
 * {@code SourceEndPacket} (speaker disconnected - tears everything down for good).
 */
final class VoiceSource {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_SIZE = 960; // 20ms @ 48kHz mono

    private final UUID sourceId;
    private final PlaybackManager playbackManager;
    private final JavaOpusDecoder decoder;
    private final SimpleJitterBuffer jitterBuffer;

    private volatile long lastPacketMillis;
    private volatile boolean segmentActive;

    VoiceSource(@NotNull UUID sourceId, @NotNull PlaybackManager playbackManager) {
        this.sourceId = sourceId;
        this.playbackManager = playbackManager;
        this.decoder = new JavaOpusDecoder(SAMPLE_RATE, false, FRAME_SIZE);
        this.jitterBuffer = new SimpleJitterBuffer(frame -> playbackManager.submit(sourceId, frame));
    }

    void create(int distance) throws CodecException {
        decoder.open();
        jitterBuffer.start();
        playbackManager.createSource(sourceId, distance);

        lastPacketMillis = System.currentTimeMillis();
        segmentActive = true;
        GtnhVoice.LOG.info("[VoiceSource] Created for sourceId={}", sourceId);
    }

    void handleAudio(byte[] opusData, double x, double y, double z) {
        lastPacketMillis = System.currentTimeMillis();
        if (!segmentActive) {
            segmentActive = true;
            GtnhVoice.LOG.info("[VoiceSource] Segment resumed for sourceId={}", sourceId);
        }

        playbackManager.updateSourcePosition(sourceId, x, y, z);

        try {
            short[] decoded = decoder.decode(opusData);
            jitterBuffer.push(decoded);
        } catch (CodecException e) {
            GtnhVoice.LOG.error("[VoiceSource] Failed to decode frame for sourceId={}", sourceId, e);
        }
    }

    /**
     * Called periodically by the manager's watchdog. Resets the speech segment once, the first time this source is
     * found idle past {@code timeoutMillis}; stays quiet on subsequent checks until a new packet arrives.
     */
    void checkInactivity(long now, long timeoutMillis) {
        if (segmentActive && now - lastPacketMillis > timeoutMillis) {
            segmentActive = false;
            decoder.reset();
            jitterBuffer.clear();
            playbackManager.resetSource(sourceId);
            GtnhVoice.LOG
                .info("[VoiceSource] Inactivity reset for sourceId={} (idle {}ms)", sourceId, now - lastPacketMillis);
        }
    }

    void destroy() {
        jitterBuffer.shutdown();
        decoder.close();
        playbackManager.destroySource(sourceId);
        GtnhVoice.LOG.info("[VoiceSource] Destroyed for sourceId={}", sourceId);
    }
}
