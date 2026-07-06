package com.enn3developer.gtnhvoice.client.source;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.PlayerVoiceSettings;
import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.core.api.audio.codec.AudioDecoder;
import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.audio.jitter.AdaptiveJitterBuffer;

/**
 * One remote speaker's full receive pipeline: its own Opus decoder (Opus is stateful - never shared across
 * sourceIds), its own {@link AdaptiveJitterBuffer}, and its own positioned AL source (owned by the shared
 * {@link PlaybackManager}, keyed by {@link #sourceId}).
 * <p>
 * Raw (undecoded) frames are offered into the jitter buffer keyed by sequence number as packets arrive; a
 * dedicated poller thread drains it, sleeping via {@link AdaptiveJitterBuffer#awaitNextEvent} until the exact
 * moment the schedule has work (head frame due, or a concealable gap slot going overdue) rather than polling
 * on a fixed tick - emission is paced by the buffer's own 20ms due-time schedule, and an idle source's poller
 * sleeps indefinitely until the next packet arrives. Decoding happens in the order the buffer hands frames
 * back, so out-of-order UDP arrival doesn't feed the stateful decoder out of order. Isolated mid-stream packet
 * loss is masked with Opus packet loss concealment instead of stalling playback - see {@link #emitNextFrame()}
 * for the exact conditions.
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
    // 20ms base pre-buffer; the adaptive component grows it under real jitter. 1 is the floor: the buffer never
    // drops late packets, so with no base window an out-of-order frame would reach the stateful decoder late and
    // out of order. Values >= 2 are no longer needed for reordering since the jitter buffer always priority-orders
    // by sequence number.
    private static final int JITTER_PACKET_DELAY_FRAMES = 1;
    private static final long LOG_INTERVAL_MILLIS = 2_000L;
    // Upper bound on back-to-back synthesized (PLC) frames (~100ms, roughly WebRTC's expand limit): a safety
    // net against fabricating audio indefinitely if sequence numbers ever jump pathologically. In practice the
    // stream-still-flowing and head-not-yet-due conditions in emitNextFrame() bound concealment much tighter.
    private static final int MAX_CONSECUTIVE_PLC_FRAMES = 5;

    private final UUID sourceId;
    private final PlaybackManager playbackManager;
    private final DecoderFactory decoderFactory;
    private final AdaptiveJitterBuffer jitterBuffer;
    private final AtomicBoolean destroyed = new AtomicBoolean();

    private AudioDecoder decoder;
    private volatile long lastPacketMillis;
    private volatile boolean segmentActive;
    private volatile boolean running;
    // Set by the manager's watchdog thread, consumed by the poller thread: the decoder is stateful native code
    // and must only ever be touched from the poller, so an inactivity reset is requested here rather than
    // calling decoder.reset() from the watchdog while a decode may be in flight.
    private volatile boolean decoderResetPending;
    private Thread pollerThread;
    private int distance;

    // Decode bookkeeping, only ever touched from the poller thread.
    private long lastEmittedSequence = -1;
    private int consecutivePlcFrames;
    private boolean emittedSinceReset;
    private long framesEmitted;
    private long framesConcealed;

    VoiceSource(@NotNull UUID sourceId, @NotNull PlaybackManager playbackManager, DecoderFactory decoderFactory) {
        this.sourceId = sourceId;
        this.playbackManager = playbackManager;
        this.decoderFactory = decoderFactory;
        this.jitterBuffer = new AdaptiveJitterBuffer(System::currentTimeMillis, JITTER_PACKET_DELAY_FRAMES);
    }

    void create(int distance) throws CodecException {
        this.distance = distance;
        decoder = decoderFactory.create(SAMPLE_RATE, false, FRAME_SIZE);

        running = true;
        pollerThread = new Thread(this::runPoller, "gtnhvoice-jitterbuffer-" + sourceId);
        pollerThread.setDaemon(true);
        pollerThread.start();

        playbackManager.createSource(
            sourceId,
            distance,
            PlayerVoiceSettings.getInstance()
                .getVolume(sourceId));

        lastPacketMillis = System.currentTimeMillis();
        segmentActive = true;
        GtnhVoice.LOG.info("[VoiceSource] Created for sourceId={}", sourceId);
    }

    void handleAudio(long sequenceNumber, byte[] opusData, double x, double y, double z, boolean positional) {
        lastPacketMillis = System.currentTimeMillis();
        if (!segmentActive) {
            segmentActive = true;
            GtnhVoice.LOG.info("[VoiceSource] Segment resumed for sourceId={}", sourceId);
        }

        // Idempotent no-op in the common case (createSource() is documented safe to call repeatedly); this is
        // what makes AL-source recreation after an output-device/HRTF rebuild "lazy" - if the rebuild wiped our
        // positioned AL source, this call is what re-creates it on the new context, without this VoiceSource ever
        // needing to know a rebuild happened or touching its decoder/jitter state. Re-reading the gain from
        // PlayerVoiceSettings fresh on every call (rather than caching it) is what makes that recreation also
        // pick up the correct volume instead of silently resetting to 100%.
        playbackManager.createSource(
            sourceId,
            distance,
            PlayerVoiceSettings.getInstance()
                .getVolume(sourceId));
        // Per packet, not per source: the server-side group decides positional-vs-flat frame by frame, so a
        // speaker switching groups mid-stream flips the existing AL source's mode without teardown. The position
        // is recorded even for flat frames so a flip back to positional resumes from a fresh location.
        playbackManager.setPositional(sourceId, positional);
        playbackManager.updateSourcePosition(sourceId, x, y, z);
        jitterBuffer.offer(sequenceNumber, opusData);
    }

    /**
     * Called periodically by the manager's watchdog. Resets the speech segment once, the first time this source is
     * found idle past {@code timeoutMillis}; stays quiet on subsequent checks until a new packet arrives.
     */
    void checkInactivity(long now, long timeoutMillis) {
        if (!segmentActive) return;
        if (now - lastPacketMillis <= timeoutMillis) return;

        segmentActive = false;
        decoderResetPending = true; // applied by the poller thread, which owns the decoder
        jitterBuffer.clear();
        playbackManager.resetSource(sourceId);
        GtnhVoice.LOG
            .info("[VoiceSource] Inactivity reset for sourceId={} (idle {}ms)", sourceId, now - lastPacketMillis);
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
        if (!destroyed.compareAndSet(false, true)) return;
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
        long lastLogTime = System.currentTimeMillis();

        while (running) {
            if (decoderResetPending) {
                decoderResetPending = false;
                decoder.reset();
                consecutivePlcFrames = 0;
                // Block concealment until the next segment's first real frame anchors the decoder again -
                // otherwise the stale lastEmittedSequence would look like a huge "gap" to conceal.
                emittedSinceReset = false;
            }

            boolean emitted = emitNextFrame();

            long now = System.currentTimeMillis();
            if (now - lastLogTime >= LOG_INTERVAL_MILLIS) {
                GtnhVoice.LOG.info(
                    "[JitterBuffer] sourceId={} buffered={} targetDelayMs={} framesEmitted={} framesConcealed={}",
                    sourceId,
                    jitterBuffer.size(),
                    jitterBuffer.currentTargetDelayMillis(),
                    framesEmitted,
                    framesConcealed);
                lastLogTime = now;
            }

            // Drain everything already due before blocking, so a backlog never waits a schedule slot per frame.
            if (emitted) continue;

            try {
                jitterBuffer.awaitNextEvent(concealDeadlineSequence());
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * The slot whose overdue time should also wake the poller (a concealable gap), or -1 while packet-loss
     * concealment isn't currently eligible. Mirrors the consumer-side conditions of
     * {@link #shouldConcealGap(long)}; the buffer-side ones (a later frame actually buffered) are evaluated by
     * {@link AdaptiveJitterBuffer#awaitNextEvent} itself.
     */
    private long concealDeadlineSequence() {
        if (!emittedSinceReset) return -1;
        if (consecutivePlcFrames >= MAX_CONSECUTIVE_PLC_FRAMES) return -1;

        return lastEmittedSequence + 1;
    }

    /**
     * Emits at most one 20ms frame into playback: the next in-order frame if it's due, else a
     * packet-loss-concealment frame for a genuinely missing slot. Returns whether anything was consumed, so
     * the caller drains all currently-due work before blocking again. Concealment only fires for isolated
     * mid-stream loss - the missing slot's playback time has passed, a later frame is already buffered (proof
     * the stream is still flowing, so this isn't the sender pausing), and that later frame isn't itself due yet
     * (if it is, the whole gap is stale and we skip ahead rather than replaying the outage late and dragging
     * extra latency behind us for the rest of the segment).
     */
    private boolean emitNextFrame() {
        // Frames at or below the last emitted sequence arrived too late - their slot was already played or
        // concealed, and decoding them now would feed the stateful decoder out of order.
        jitterBuffer.discardThrough(lastEmittedSequence);

        Long headSequence = jitterBuffer.peekSequenceNumber();
        if (headSequence == null) return false; // nothing buffered: sender pause or stream end - never concealed

        if (headSequence > lastEmittedSequence + 1 && shouldConcealGap(headSequence)) {
            emitConcealment();
            return true;
        }

        AdaptiveJitterBuffer.Frame frame = jitterBuffer.poll();
        if (frame == null) return false; // head frame isn't due yet (pre-buffering phase)

        lastEmittedSequence = frame.sequenceNumber;
        consecutivePlcFrames = 0;
        try {
            short[] decoded = decoder.decode(frame.data);
            playbackManager.submit(sourceId, decoded);
            framesEmitted++;
            emittedSinceReset = true;
        } catch (CodecException e) {
            GtnhVoice.LOG.error("[VoiceSource] Failed to decode frame for sourceId={}", sourceId, e);
        }
        return true; // the frame was consumed from the buffer either way - that's progress
    }

    private boolean shouldConcealGap(long headSequence) {
        if (!emittedSinceReset) return false; // never synthesize ahead of a segment's first real frame
        if (consecutivePlcFrames >= MAX_CONSECUTIVE_PLC_FRAMES) return false;
        if (!jitterBuffer.isSequenceOverdue(lastEmittedSequence + 1)) return false;

        // If the buffered head is itself already due, the entire gap is stale (e.g. a burst outage) - skip
        // ahead to real audio instead of stacking concealment frames in front of it.
        return !jitterBuffer.isSequenceOverdue(headSequence);
    }

    private void emitConcealment() {
        // The slot is consumed either way, so a late arrival for it gets discarded rather than decoded out of
        // order after the concealment frame already advanced the decoder state.
        lastEmittedSequence++;
        consecutivePlcFrames++;

        try {
            short[] concealed = decoder.decode(null); // null input = Opus packet loss concealment
            playbackManager.submit(sourceId, concealed);
            framesConcealed++;
        } catch (CodecException e) {
            GtnhVoice.LOG.error("[VoiceSource] Failed to conceal lost frame for sourceId={}", sourceId, e);
        }
    }
}
