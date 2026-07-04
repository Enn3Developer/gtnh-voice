package com.enn3developer.gtnhvoice.client.source;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.core.api.audio.codec.AudioDecoder;
import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.audio.codec.opus.OpusCodecSupplier;
import com.enn3developer.gtnhvoice.core.audio.jitter.AdaptiveJitterBuffer;

/**
 * One remote speaker's full receive pipeline: its own Opus decoder (Opus is stateful - never shared across
 * sourceIds), its own {@link AdaptiveJitterBuffer}, and its own positioned AL source (owned by the shared
 * {@link PlaybackManager}, keyed by {@link #sourceId}).
 * <p>
 * Raw (undecoded) frames are offered into the jitter buffer keyed by sequence number as packets arrive; a
 * dedicated poller thread drains it once per 20ms playback tick and decodes in the order the buffer hands frames
 * back, so out-of-order UDP arrival doesn't feed the stateful decoder out of order.
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
    private static final int JITTER_PACKET_DELAY_FRAMES = 2; // 40ms initial pre-buffer; adapts from there
    private static final long TICK_MILLIS = 20L;
    private static final long LOG_INTERVAL_MILLIS = 2_000L;

    private final UUID sourceId;
    private final PlaybackManager playbackManager;
    private final AdaptiveJitterBuffer jitterBuffer;

    private AudioDecoder decoder;
    private volatile long lastPacketMillis;
    private volatile boolean segmentActive;
    private volatile boolean running;
    private Thread pollerThread;

    VoiceSource(@NotNull UUID sourceId, @NotNull PlaybackManager playbackManager) {
        this.sourceId = sourceId;
        this.playbackManager = playbackManager;
        this.jitterBuffer = new AdaptiveJitterBuffer(System::currentTimeMillis, JITTER_PACKET_DELAY_FRAMES);
    }

    void create(int distance) throws CodecException {
        decoder = OpusCodecSupplier.createDecoder(SAMPLE_RATE, false, FRAME_SIZE);

        running = true;
        pollerThread = new Thread(this::runPoller, "gtnhvoice-jitterbuffer-" + sourceId);
        pollerThread.setDaemon(true);
        pollerThread.start();

        playbackManager.createSource(sourceId, distance);

        lastPacketMillis = System.currentTimeMillis();
        segmentActive = true;
        GtnhVoice.LOG.info("[VoiceSource] Created for sourceId={}", sourceId);
    }

    void handleAudio(long sequenceNumber, byte[] opusData, double x, double y, double z) {
        lastPacketMillis = System.currentTimeMillis();
        if (!segmentActive) {
            segmentActive = true;
            GtnhVoice.LOG.info("[VoiceSource] Segment resumed for sourceId={}", sourceId);
        }

        playbackManager.updateSourcePosition(sourceId, x, y, z);
        jitterBuffer.offer(sequenceNumber, opusData);
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

    /**
     * Whether this source is currently in an active speech segment (frames have arrived within the
     * inactivity timeout). Read by {@link VoiceSourceManager#getSpeakingSourceIds()} for the who's-talking HUD -
     * reuses this existing lifecycle flag rather than adding a separate speaking-detection mechanism.
     */
    boolean isSegmentActive() {
        return segmentActive;
    }

    void destroy() {
        running = false;
        if (pollerThread != null) {
            pollerThread.interrupt();
            try {
                pollerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
            pollerThread = null;
        }

        decoder.close();
        playbackManager.destroySource(sourceId);
        GtnhVoice.LOG.info("[VoiceSource] Destroyed for sourceId={}", sourceId);
    }

    private void runPoller() {
        long framesEmitted = 0;
        long lastLogTime = System.currentTimeMillis();

        while (running) {
            AdaptiveJitterBuffer.Frame frame = jitterBuffer.poll();
            if (frame != null) {
                try {
                    short[] decoded = decoder.decode(frame.data);
                    playbackManager.submit(sourceId, decoded);
                    framesEmitted++;
                } catch (CodecException e) {
                    GtnhVoice.LOG.error("[VoiceSource] Failed to decode frame for sourceId={}", sourceId, e);
                }
            }

            long now = System.currentTimeMillis();
            if (now - lastLogTime >= LOG_INTERVAL_MILLIS) {
                GtnhVoice.LOG.info(
                    "[JitterBuffer] sourceId={} buffered={} targetDelayMs={} framesEmitted={}",
                    sourceId,
                    jitterBuffer.size(),
                    jitterBuffer.currentTargetDelayMillis(),
                    framesEmitted);
                lastLogTime = now;
            }

            try {
                Thread.sleep(TICK_MILLIS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
