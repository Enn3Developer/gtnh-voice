package com.enn3developer.gtnhvoice.client.playback;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.api.client.ISourceMetadata;
import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;

/**
 * Owns the lifecycle of the {@link PlaybackThread} and the per-source frame/position hand-off. Nothing touches
 * OpenAL until {@link #start(String, Config.HrtfMode)} is called. A single dedicated ALC device+context+thread is
 * shared across every
 * {@code VoiceSource} that registers here - each gets its own positioned AL source, not its own device.
 * <p>
 * Frame queues and positions are kept in plain {@link ConcurrentHashMap}s so the hot path ({@link #submit} /
 * {@link #updateSourcePosition}, called from the network receive path every ~20ms per active source) never has to
 * cross onto the playback thread. Only the AL object lifecycle (create/destroy/reset) is marshalled onto
 * {@link PlaybackThread} via its command queue, since only that thread may call {@code AL10}/{@code ALC10}
 * functions.
 */
public class PlaybackManager {

    private static final int QUEUE_CAPACITY = 50; // ~1s of 20ms frames
    private static final int FRAME_SAMPLES = 960; // 20ms @ 48kHz mono
    private static final long FILTER_ERROR_LOG_INTERVAL_MILLIS = 1_000L;

    private final Map<UUID, BlockingQueue<short[]>> frameQueues = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> positions = new ConcurrentHashMap<>();
    private final Map<UUID, Float> gains = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> positionalModes = new ConcurrentHashMap<>();

    // Lives on the manager (not the thread) so registrations survive start()/stop() cycles and rebuilds; the
    // current PlaybackThread reads it through its manager reference at every fire site. CopyOnWrite so the
    // playback thread iterates a stable snapshot while other threads register/unregister concurrently.
    private final List<PlaybackLifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();

    // Same placement rationale as the lifecycle listeners above; iterated per frame on the decode path, where
    // CopyOnWrite's snapshot iteration is free and registration churn is rare.
    private final List<PlaybackPcmFilter> pcmFilters = new CopyOnWriteArrayList<>();
    private final AtomicLong lastFilterErrorLogMillis = new AtomicLong();

    private volatile ListenerSnapshot listenerSnapshot = ListenerSnapshot.ORIGIN;
    // Volatile because the AudioThreadExecutor seam reads it from arbitrary (future addon) threads while the
    // client thread swaps it in start()/stop() - a stale read would silently reject or misroute commands.
    private volatile PlaybackThread playbackThread;

    public boolean isPlaying() {
        return playbackThread != null && playbackThread.isAlive();
    }

    public void start(String deviceName, Config.HrtfMode hrtfMode) {
        if (isPlaying()) return;

        frameQueues.clear();
        positions.clear();
        gains.clear();
        positionalModes.clear();
        listenerSnapshot = ListenerSnapshot.ORIGIN;
        playbackThread = new PlaybackThread(this, deviceName, hrtfMode);
        playbackThread.start();
        GtnhVoice.LOG
            .info("[Playback] Started (device={}, hrtf={})", deviceName == null ? "<default>" : deviceName, hrtfMode);
    }

    public void stop() {
        if (playbackThread == null) return;

        playbackThread.shutdown();
        try {
            playbackThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
        playbackThread = null;
        frameQueues.clear();
        positions.clear();
        gains.clear();
        positionalModes.clear();
        GtnhVoice.LOG.info("[Playback] Stopped");
    }

    /**
     * Lazily registers a new positioned AL source for {@code sourceId}, seeded with {@code gain} the first time
     * it's created for this session (subsequent calls, including the one that lazily recreates the AL source
     * after an output-device/HRTF rebuild - see {@code VoiceSource#handleAudio} - are no-ops on this map, since
     * the caller always re-derives the current value from {@code PlayerVoiceSettings} before calling, not a
     * cached one). Safe to call repeatedly; only the first call for a given id has any AL effect.
     */
    public void createSource(UUID sourceId, int distance, float gain) {
        if (!isPlaying()) return;

        BlockingQueue<short[]> queue = frameQueues
            .computeIfAbsent(sourceId, id -> new ArrayBlockingQueue<>(QUEUE_CAPACITY));
        positions.putIfAbsent(sourceId, new double[] { 0, 0, 0 });
        gains.putIfAbsent(sourceId, gain);
        positionalModes.putIfAbsent(sourceId, Boolean.TRUE);

        playbackThread
            .enqueueCommand(() -> playbackThread.createSourceChannel(sourceId, queue, distance, gains.get(sourceId)));
    }

    /**
     * Fully tears down {@code sourceId}'s AL source and frees its handle. Used when the speaker disconnects
     * ({@code SourceEndPacket}).
     */
    public void destroySource(UUID sourceId) {
        frameQueues.remove(sourceId);
        positions.remove(sourceId);
        gains.remove(sourceId);
        positionalModes.remove(sourceId);

        if (!isPlaying()) return;
        playbackThread.enqueueCommand(() -> playbackThread.destroySourceChannel(sourceId));
    }

    /**
     * Live gain hotswap for an already-registered source (no-op if {@code sourceId} has no AL source yet - a
     * volume change for an offline/never-spoken player only needs to update {@code PlayerVoiceSettings}, which
     * {@link #createSource} will read fresh whenever a source does get created). Posts an {@code AL_GAIN} update to
     * the playback thread so the change is audible immediately.
     */
    public void setGain(UUID sourceId, float gain) {
        if (!frameQueues.containsKey(sourceId)) return;

        gains.put(sourceId, gain);
        if (!isPlaying()) return;
        playbackThread.enqueueCommand(() -> playbackThread.applyGain(sourceId, gain));
    }

    /**
     * Stops the source and drops any queued audio without deleting the AL source itself. Used on speech-segment
     * inactivity timeout so a resumed segment starts clean instead of the first frame bridging an intentional pause.
     */
    public void resetSource(UUID sourceId) {
        BlockingQueue<short[]> queue = frameQueues.get(sourceId);
        if (queue != null) queue.clear();

        if (!isPlaying()) return;
        playbackThread.enqueueCommand(() -> playbackThread.resetSourceChannel(sourceId));
    }

    /**
     * Submits a decoded 960-sample mono PCM frame for {@code sourceId}. The frame first passes through the
     * registered {@link PlaybackPcmFilter} chain (on this calling thread - see the interface for the full
     * threading and isolation contract), then drops the oldest queued frame if that source's queue is full, to
     * keep playback latency bounded rather than growing unboundedly under sustained overload. No-op if the
     * source hasn't been registered via {@link #createSource(UUID, int, float)}.
     */
    public void submit(UUID sourceId, short[] frame) {
        BlockingQueue<short[]> queue = frameQueues.get(sourceId);
        if (queue == null) return;

        frame = applyPcmFilters(sourceId, frame);
        if (!queue.offer(frame)) {
            queue.poll();
            queue.offer(frame);
        }
    }

    /**
     * Runs {@code frame} through the filter chain in registration order, feeding each filter's output to the
     * next. A filter that throws, returns {@code null}, or returns a wrong-length array is skipped for this
     * frame - the current frame continues unfiltered into the rest of the chain - with an error log throttled to
     * one per {@value #FILTER_ERROR_LOG_INTERVAL_MILLIS}ms, mirroring {@code PlaybackThread}'s command isolation
     * (minus its AL error drain, which has no business on this non-AL thread). With no filters registered this
     * is a single {@code isEmpty} check.
     */
    private short[] applyPcmFilters(UUID sourceId, short[] frame) {
        if (pcmFilters.isEmpty()) return frame;

        for (PlaybackPcmFilter filter : pcmFilters) {
            try {
                short[] processed = filter.process(sourceId, frame);
                if (processed != null && processed.length == FRAME_SAMPLES) {
                    frame = processed;
                    continue;
                }
                logFilterFailure(filter, "returned " + describeBadOutput(processed), null);
            } catch (Throwable t) {
                logFilterFailure(filter, "threw", t);
            }
        }
        return frame;
    }

    private static String describeBadOutput(short[] processed) {
        return processed == null ? "null" : processed.length + " samples instead of " + FRAME_SAMPLES;
    }

    private void logFilterFailure(PlaybackPcmFilter filter, String what, Throwable cause) {
        if (!LogThrottle.shouldLog(lastFilterErrorLogMillis, FILTER_ERROR_LOG_INTERVAL_MILLIS)) return;
        GtnhVoice.LOG.error("[Playback] PCM filter {} {}; skipped for this frame", filter, what, cause);
    }

    /**
     * Records the latest known absolute world position of {@code sourceId}'s speaker. Picked up by the playback
     * thread on its next loop iteration and applied to that source's AL position.
     */
    public void updateSourcePosition(UUID sourceId, double x, double y, double z) {
        if (!frameQueues.containsKey(sourceId)) return;
        positions.put(sourceId, new double[] { x, y, z });
    }

    /**
     * Records whether {@code sourceId} should play positionally (proximity/3D) or flat (full gain, no
     * spatialization). Arrives with every audio packet - the server-side group decides per frame - and is picked
     * up by the playback thread on its next loop iteration, which flips the existing AL source in place only when
     * the mode actually changed. No-op if the source hasn't been registered via {@link #createSource}.
     */
    public void setPositional(UUID sourceId, boolean positional) {
        if (!frameQueues.containsKey(sourceId)) return;
        positionalModes.put(sourceId, positional);
    }

    /**
     * Point-in-time spatial snapshot of {@code sourceId}: the speaker's last known absolute world position and
     * whether the source currently plays positionally. Empty for an id that was never registered via
     * {@link #createSource} or has since been torn down ({@link #destroySource} removes the map entries;
     * {@link #stop} clears them).
     * <p>
     * Callable from any thread (typically inside {@link PlaybackLifecycleListener#audioTick()}). The position
     * triple is always internally consistent - {@link #updateSourcePosition} replaces the array wholesale, never
     * mutates it - but position and positional flag are read without a common lock, so the two may come from
     * instants up to one packet (~20ms) apart. That relaxed consistency is deliberate: it's plenty for
     * spatial-audio consumers, and locking the hot receive path to do better isn't worth it. The returned
     * snapshot is a detached copy - later updates never mutate an already-returned instance.
     * <p>
     * A voice {@code sourceId} IS the speaking player's UUID (see {@code VoiceClientManager#resolveName}), so
     * consumers needing the player entity or name resolve it directly; this query deliberately duplicates no
     * roster data. Fresh-source edge: {@link #createSource} seeds the position with {@code (0,0,0)} until the
     * first audio packet lands (~20ms), so a source reporting exactly the origin may simply be brand new -
     * consumers doing raycasts should treat it with suspicion or wait a tick.
     */
    Optional<SourceMetadata> sourceMetadata(UUID sourceId) {
        double[] position = positions.get(sourceId);
        if (position == null) return Optional.empty();

        Boolean positional = positionalModes.get(sourceId);
        // A concurrent destroySource can have removed the mode between the two reads - the source is gone.
        if (positional == null) return Optional.empty();

        return Optional.of(new SourceMetadata(position[0], position[1], position[2], positional));
    }

    /**
     * Publishes the local player's absolute position/look direction, driving the shared AL listener. Called from
     * the client tick; read by the playback thread every loop iteration.
     */
    public void updateListener(double x, double y, double z, float lookX, float lookY, float lookZ) {
        listenerSnapshot = new ListenerSnapshot(x, y, z, lookX, lookY, lookZ);
    }

    /**
     * Control-API entry point (driven by the settings GUI via {@code AudioDeviceController}): rebuilds the output
     * device and/or HRTF mode live, without tearing down any {@code VoiceSource}. No-op (besides a log line) if
     * playback
     * isn't currently running - the new selection still gets picked up by the next {@link #start}, since callers
     * are expected to persist it to {@link Config} regardless of whether a rebuild happens here.
     */
    public void rebuildOutput(String deviceName, Config.HrtfMode hrtfMode) {
        if (!isPlaying()) {
            GtnhVoice.LOG.info(
                "[Playback] Rebuild requested (device={}, hrtf={}) but playback isn't running; will apply on next start",
                deviceName == null ? "<default>" : deviceName,
                hrtfMode);
            return;
        }

        playbackThread.requestRebuild(deviceName, hrtfMode);
    }

    /**
     * The {@link AudioThreadExecutor} seam a future public {@code runOnAudioThread} addon API will wrap. Every
     * returned executor targets whatever {@link PlaybackThread} is current at submission time (surviving
     * {@link #start}/{@link #stop} cycles) and rejects (returns {@code false}) whenever playback isn't running.
     */
    AudioThreadExecutor audioThreadExecutor() {
        return command -> {
            // Null is a caller bug and must fail the same way whether or not playback is running - don't let
            // the thread-null short-circuit below hide it while the game is in the menu.
            Objects.requireNonNull(command, "command");
            PlaybackThread thread = playbackThread;
            return thread != null && thread.enqueueCommand(command);
        };
    }

    /**
     * API-backing seam for the public client API's {@code runOnAudioThread}: submits {@code command} (non-null,
     * or {@link NullPointerException}) through {@link #audioThreadExecutor()} - see there and
     * {@link AudioThreadExecutor#execute} for the acceptance-not-guarantee and isolation contract. Public only
     * so the API backend outside this package can reach the seam without widening
     * {@link AudioThreadExecutor} itself; internal callers keep using {@link #audioThreadExecutor()}.
     */
    public boolean runOnAudioThread(Runnable command) {
        return audioThreadExecutor().execute(command);
    }

    /**
     * API-backing seam for the public client API's source-metadata query: {@link #sourceMetadata} widened to
     * the public {@link ISourceMetadata} view - same consistency and freshness contract as documented there.
     */
    public Optional<ISourceMetadata> sourceMetadataFor(UUID sourceId) {
        return sourceMetadata(sourceId).map(ISourceMetadata.class::cast);
    }

    /**
     * Registers a context lifecycle listener - see {@link PlaybackLifecycleListener} for the threading and
     * pairing contract. Callable from any thread; the registration outlives {@link #start}/{@link #stop} cycles.
     * Registering while a context is already live does NOT retroactively fire {@code contextCreated} - the
     * listener only sees transitions from the next lifecycle event onward.
     */
    void addLifecycleListener(PlaybackLifecycleListener listener) {
        Objects.requireNonNull(listener, "listener");
        lifecycleListeners.add(listener);
    }

    /** Unregisters a previously added lifecycle listener; no-op if it was never registered. */
    void removeLifecycleListener(PlaybackLifecycleListener listener) {
        lifecycleListeners.remove(listener);
    }

    /**
     * Registers a PCM filter on the playback path - see {@link PlaybackPcmFilter} for the threading and failure
     * contract. Callable from any thread; like lifecycle listeners, the registration outlives
     * {@link #start}/{@link #stop} cycles. Chain order is registration order, deterministically - a priority
     * scheme is deliberately not provided until a real consumer needs one.
     */
    void addPcmFilter(PlaybackPcmFilter filter) {
        Objects.requireNonNull(filter, "filter");
        pcmFilters.add(filter);
    }

    /** Unregisters a previously added PCM filter; no-op if it was never registered. */
    void removePcmFilter(PlaybackPcmFilter filter) {
        pcmFilters.remove(filter);
    }

    List<PlaybackLifecycleListener> lifecycleListenersView() {
        return lifecycleListeners;
    }

    Map<UUID, double[]> positionsView() {
        return positions;
    }

    Map<UUID, BlockingQueue<short[]>> frameQueuesView() {
        return frameQueues;
    }

    Map<UUID, Boolean> positionalModesView() {
        return positionalModes;
    }

    ListenerSnapshot currentListenerSnapshot() {
        return listenerSnapshot;
    }
}
