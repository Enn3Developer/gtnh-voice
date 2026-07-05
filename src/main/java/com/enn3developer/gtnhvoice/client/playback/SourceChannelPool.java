package com.enn3developer.gtnhvoice.client.playback;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import org.lwjgl.openal.AL10;

import com.enn3developer.gtnhvoice.GtnhVoice;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Owns the playback thread's per-source AL channels: the {@code sourceId -> SourceChannel} map, channel
 * create/destroy/reset/gain, the per-iteration pump, and whole-map teardown. Reads frame/position/mode hand-off
 * from {@link PlaybackManager}'s concurrent maps and announces source lifecycle through the shared
 * {@link LifecycleEventDispatcher} at exactly the internal points documented on each method.
 * <p>
 * All instance state is thread-confined to the playback thread - every real access happens from
 * {@link PlaybackThread#run} or from commands run inline within it (see the {@link #sourceChannels} note for the
 * one test-only exception), so the plain {@link HashMap} needs no synchronization.
 */
@Lwjgl3Aware
final class SourceChannelPool {

    static final int SAMPLE_RATE = 48_000;
    static final int BUFFER_POOL_SIZE = 6;
    private static final float REFERENCE_DISTANCE = 1.0f;
    private static final float ROLLOFF_FACTOR = 1.0f;

    // Prime-and-hysteresis tuning for AL source start, see pumpSourceChannel(). 2 is the floor: one buffer
    // playing plus one queued as cushion against decode-poller scheduling slop - the poller's frame cadence is
    // Thread.sleep-based, not phase-locked to the AL playback clock, so with a single buffer any wakeup drift
    // underruns the source. Lowered from 3 once VoiceSource gained packet-loss concealment, which keeps frames
    // flowing through genuine packet gaps instead of relying on queue depth to ride them out.
    private static final int PRIME_BUFFERS = 2;
    private static final long TAIL_FLUSH_MILLIS = 60L;

    private final PlaybackManager manager;
    private final LifecycleEventDispatcher dispatcher;
    // Package-private only so fireContextTeardown's sources-then-context ordering and fireAudioTick's call-site
    // guard are unit-testable with a seeded map and no AL device; every real access happens on the playback
    // thread (see the thread-confinement note in the class javadoc).
    final Map<UUID, SourceChannel> sourceChannels = new HashMap<>();

    SourceChannelPool(PlaybackManager manager, LifecycleEventDispatcher dispatcher) {
        this.manager = manager;
        this.dispatcher = dispatcher;
    }

    boolean isEmpty() {
        return sourceChannels.isEmpty();
    }

    /**
     * Live view of the channel map for {@link PlaybackThread#run}'s pump loop and
     * {@link LifecycleEventDispatcher#fireContextTeardown}'s announcement iteration. Playback-thread-only, like
     * everything else here.
     */
    Map<UUID, SourceChannel> channelsView() {
        return sourceChannels;
    }

    /**
     * Runs on the playback thread only (queued via {@link PlaybackThread#enqueueCommand}): allocates a positioned
     * AL source + buffer pool for a newly seen {@code sourceId}. No-op if one already exists - including right
     * after a rebuild wiped the pool, in which case this is exactly what lazily recreates it (and what naturally
     * gives lifecycle listeners a fresh {@link LifecycleEventDispatcher#fireSourceCreated} with the new handle on
     * the new context).
     */
    void createSourceChannel(UUID sourceId, BlockingQueue<short[]> frameQueue, int distance, float gain) {
        if (sourceChannels.containsKey(sourceId)) return;

        int source = AL10.alGenSources();
        if (!AlDebug.checkAlError("alGenSources")) return;

        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, REFERENCE_DISTANCE);
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, distance);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, ROLLOFF_FACTOR);
        AL10.alSourcef(source, AL10.AL_GAIN, gain);

        int[] bufferIds = new int[BUFFER_POOL_SIZE];
        AL10.alGenBuffers(bufferIds);
        if (!AlDebug.checkAlError("alGenBuffers")) {
            AL10.alDeleteSources(source);
            return;
        }

        Deque<Integer> freeBuffers = new ArrayDeque<>(BUFFER_POOL_SIZE);
        for (int bufferId : bufferIds) freeBuffers.add(bufferId);

        sourceChannels.put(sourceId, new SourceChannel(source, bufferIds, freeBuffers, frameQueue));
        dispatcher.fireSourceCreated(sourceId, source);
        GtnhVoice.LOG.info("[Playback] AL source created for sourceId={} gain={}", sourceId, gain);
    }

    /**
     * Runs on the playback thread only (queued via {@link PlaybackManager#setGain}): applies a live
     * {@code AL_GAIN} update to {@code sourceId}'s AL source. No-op if it doesn't currently have one - the value
     * is still recorded in {@link PlaybackManager}'s gain map and will be picked up whenever
     * {@link #createSourceChannel} next runs for it (e.g. the player starts speaking, or a rebuild recreates the
     * channel).
     */
    void applyGain(UUID sourceId, float gain) {
        SourceChannel channel = sourceChannels.get(sourceId);
        if (channel == null) return;

        AL10.alSourcef(channel.alSource, AL10.AL_GAIN, gain);
        AlDebug.checkAlError("applyGain");
        GtnhVoice.LOG.info("[Playback] Gain applied for sourceId={} gain={}", sourceId, gain);
    }

    /**
     * Runs on the playback thread only: fully stops, unqueues, and deletes {@code sourceId}'s AL source and
     * buffers, freeing the handles. Used when the speaker disconnects. Announces
     * {@link LifecycleEventDispatcher#fireSourceDestroying} first, while the handle is still fully valid; the
     * remove-before-fire discipline keeps a later mass teardown from re-announcing this source.
     */
    void destroySourceChannel(UUID sourceId) {
        SourceChannel channel = sourceChannels.remove(sourceId);
        if (channel == null) return;

        dispatcher.fireSourceDestroying(sourceId, channel.alSource);
        stopAndFlush(channel);
        AL10.alDeleteSources(channel.alSource);
        AL10.alDeleteBuffers(channel.bufferIds);
        AlDebug.checkAlError("destroySourceChannel");
        GtnhVoice.LOG.info("[Playback] AL source destroyed for sourceId={}", sourceId);
    }

    /**
     * Runs on the playback thread only: stops {@code sourceId}'s AL source and returns its queued buffers to the
     * free pool, but keeps the AL source handle alive. Used on speech-segment inactivity reset. Deliberately fires
     * no lifecycle event: the AL source survives a reset, so listener state attached to the handle stays valid -
     * see {@link PlaybackLifecycleListener}.
     */
    void resetSourceChannel(UUID sourceId) {
        SourceChannel channel = sourceChannels.get(sourceId);
        if (channel == null) return;

        stopAndFlush(channel);
        GtnhVoice.LOG.info("[Playback] AL source reset for sourceId={}", sourceId);
    }

    /**
     * Runs on the playback thread only, once per pump iteration per channel: recycles processed buffers, applies
     * mode/position hand-off from {@link PlaybackManager}'s maps, queues freshly decoded frames, and starts the
     * source once primed (or tail-flushes a short trailing remainder).
     */
    void pumpSourceChannel(UUID sourceId, SourceChannel channel, long now) {
        int processed = AL10.alGetSourcei(channel.alSource, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            channel.freeBuffers.add(AL10.alSourceUnqueueBuffers(channel.alSource));
        }
        AlDebug.checkAlError("alSourceUnqueueBuffers");

        // OpenAL marks a STOPPED source's entire queue as processed instantly, even buffers that never played -
        // so a source left STOPPED must be rewound to INITIAL before any new buffer is queued onto it, or the
        // next tick's unqueue-processed step above will silently discard it before it ever primes.
        if (AL10.alGetSourcei(channel.alSource, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
            int leftoverQueued = AL10.alGetSourcei(channel.alSource, AL10.AL_BUFFERS_QUEUED);
            if (leftoverQueued > 0) {
                GtnhVoice.LOG.warn(
                    "[Playback] sourceId={} found STOPPED with {} buffers still queued after unqueue - a reset path was missed",
                    sourceId,
                    leftoverQueued);
            }
            channel.underruns++;
            AL10.alSourceRewind(channel.alSource);
            AlDebug.checkAlError("alSourceRewind");
        }

        Boolean positionalMode = manager.positionalModesView()
            .get(sourceId);
        boolean positional = positionalMode == null || positionalMode;
        if (positional != channel.positional) {
            applySourceMode(channel, sourceId, positional);
        }

        if (channel.positional) {
            double[] position = manager.positionsView()
                .get(sourceId);
            if (position != null) {
                AL10.alSource3f(
                    channel.alSource,
                    AL10.AL_POSITION,
                    (float) position[0],
                    (float) position[1],
                    (float) position[2]);
            }
        }

        while (!channel.freeBuffers.isEmpty()) {
            short[] frame = channel.frameQueue.poll();
            if (frame == null) break;

            int bufferId = channel.freeBuffers.poll();
            AL10.alBufferData(bufferId, AL10.AL_FORMAT_MONO16, frame, SAMPLE_RATE);
            AL10.alSourceQueueBuffers(channel.alSource, bufferId);
            AlDebug.checkAlError("alBufferData/alSourceQueueBuffers");
            channel.framesQueued++;
            channel.lastFrameQueuedAtMillis = now;
        }

        int queued = AL10.alGetSourcei(channel.alSource, AL10.AL_BUFFERS_QUEUED);
        int state = AL10.alGetSourcei(channel.alSource, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_PLAYING || queued == 0) return;

        boolean primed = queued >= PRIME_BUFFERS;
        boolean tailFlush = !primed && (now - channel.lastFrameQueuedAtMillis) > TAIL_FLUSH_MILLIS;
        if (!primed && !tailFlush) return;

        if (tailFlush) {
            GtnhVoice.LOG.info(
                "[Playback] tail flush sourceId={} queued={} state={}",
                sourceId,
                queued,
                AlDebug.alSourceStateToString(state));
        }
        AL10.alSourcePlay(channel.alSource);
        AlDebug.checkAlError("alSourcePlay");
    }

    /**
     * Runs on the playback thread only, from {@link #pumpSourceChannel}'s per-iteration mode check: flips one AL
     * source between positional (world-positioned, distance-attenuated - exactly how {@link #createSourceChannel}
     * builds it) and flat (listener-relative at the origin with zero rolloff, so it plays at full gain regardless
     * of where anyone stands). The desired mode arrives with every audio packet, so a speaker switching groups
     * mid-stream flips their existing source in place - no teardown, and this only executes on an actual change.
     * On a flip back to positional the pump re-applies the source's world position in the same iteration.
     */
    private void applySourceMode(SourceChannel channel, UUID sourceId, boolean positional) {
        AL10.alSourcei(channel.alSource, AL10.AL_SOURCE_RELATIVE, positional ? AL10.AL_FALSE : AL10.AL_TRUE);
        AL10.alSourcef(channel.alSource, AL10.AL_ROLLOFF_FACTOR, positional ? ROLLOFF_FACTOR : 0f);
        if (!positional) {
            AL10.alSource3f(channel.alSource, AL10.AL_POSITION, 0f, 0f, 0f);
        }
        AlDebug.checkAlError("applySourceMode");

        channel.positional = positional;
        GtnhVoice.LOG.info("[Playback] Source mode switched for sourceId={} positional={}", sourceId, positional);
    }

    private void stopAndFlush(SourceChannel channel) {
        AL10.alSourceStop(channel.alSource);
        AlDebug.checkAlError("alSourceStop");

        int queued = AL10.alGetSourcei(channel.alSource, AL10.AL_BUFFERS_QUEUED);
        for (int i = 0; i < queued; i++) {
            channel.freeBuffers.add(AL10.alSourceUnqueueBuffers(channel.alSource));
        }
        AlDebug.checkAlError("stopAndFlush unqueue");

        // Rewind STOPPED -> INITIAL so a subsequent re-prime doesn't leave freshly queued buffers sitting on a
        // STOPPED source, where OpenAL would mark them processed (and thus silently discarded) before they play.
        AL10.alSourceRewind(channel.alSource);
        AlDebug.checkAlError("alSourceRewind");

        channel.lastFrameQueuedAtMillis = 0L;
    }

    /**
     * Stops, flushes, and deletes every active {@link SourceChannel}'s AL source and buffers (step b of a
     * rebuild), leaving the pool empty. Never touches the owning {@code VoiceSource}s - their decoder/jitter
     * state lives entirely outside this class. Deliberately fires nothing: the caller announces the whole
     * teardown beforehand via {@link LifecycleEventDispatcher#fireContextTeardown}.
     */
    void teardownAlSources() {
        for (Iterator<SourceChannel> it = sourceChannels.values()
            .iterator(); it.hasNext();) {
            SourceChannel channel = it.next();
            stopAndFlush(channel);
            AL10.alDeleteSources(channel.alSource);
            AL10.alDeleteBuffers(channel.bufferIds);
            it.remove();
        }
        AlDebug.checkAlError("teardownAlSources");
    }

    /** Runs on the playback thread only, from {@link PlaybackThread#run}'s throttled logging step. */
    void logChannelsThrottled() {
        for (Map.Entry<UUID, SourceChannel> entry : sourceChannels.entrySet()) {
            SourceChannel channel = entry.getValue();
            int state = AL10.alGetSourcei(channel.alSource, AL10.AL_SOURCE_STATE);
            int queuedAl = AL10.alGetSourcei(channel.alSource, AL10.AL_BUFFERS_QUEUED);
            GtnhVoice.LOG.info(
                "[Playback] sourceId={} framesQueued={} underruns={} sourceState={} queuedAl={}",
                entry.getKey(),
                channel.framesQueued,
                channel.underruns,
                AlDebug.alSourceStateToString(state),
                queuedAl);
        }
    }

    /**
     * Per-source AL state: one positioned source, its buffer pool, and the frame queue it pulls from. Only ever
     * touched from the playback thread. Package-private (rather than private) only so tests can seed
     * {@link #sourceChannels} - see that field's note.
     */
    static final class SourceChannel {

        final int alSource;
        final int[] bufferIds;
        final Deque<Integer> freeBuffers;
        final BlockingQueue<short[]> frameQueue;

        long framesQueued;
        long underruns;
        long lastFrameQueuedAtMillis;
        // Freshly created channels are positional - that's exactly how createSourceChannel configures the AL
        // source (also after a rebuild recreates it; the pump's per-iteration mode check re-flattens it if the
        // manager's map says so). Flipped only by applySourceMode on the playback thread.
        boolean positional = true;

        SourceChannel(int alSource, int[] bufferIds, Deque<Integer> freeBuffers, BlockingQueue<short[]> frameQueue) {
            this.alSource = alSource;
            this.bufferIds = bufferIds;
            this.freeBuffers = freeBuffers;
            this.frameQueue = frameQueue;
        }
    }
}
