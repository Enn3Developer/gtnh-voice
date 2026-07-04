package com.enn3developer.gtnhvoice.client.playback;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;

/**
 * Owns the lifecycle of the {@link PlaybackThread} and the per-source frame/position hand-off. Nothing touches
 * OpenAL until {@link #start()} is called. A single dedicated ALC device+context+thread is shared across every
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

    private final Map<UUID, BlockingQueue<short[]>> frameQueues = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> positions = new ConcurrentHashMap<>();

    private volatile ListenerSnapshot listenerSnapshot = ListenerSnapshot.ORIGIN;
    private PlaybackThread playbackThread;

    public boolean isPlaying() {
        return playbackThread != null && playbackThread.isAlive();
    }

    public void start(String deviceName, Config.HrtfMode hrtfMode) {
        if (isPlaying()) return;

        frameQueues.clear();
        positions.clear();
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
        GtnhVoice.LOG.info("[Playback] Stopped");
    }

    /**
     * Lazily registers a new positioned AL source for {@code sourceId}. Safe to call multiple times; only the first
     * call for a given id has any effect.
     */
    public void createSource(UUID sourceId, int distance) {
        if (!isPlaying()) return;

        BlockingQueue<short[]> queue = frameQueues
            .computeIfAbsent(sourceId, id -> new ArrayBlockingQueue<>(QUEUE_CAPACITY));
        positions.putIfAbsent(sourceId, new double[] { 0, 0, 0 });

        playbackThread.enqueueCommand(() -> playbackThread.createSourceChannel(sourceId, queue, distance));
    }

    /**
     * Fully tears down {@code sourceId}'s AL source and frees its handle. Used when the speaker disconnects
     * ({@code SourceEndPacket}).
     */
    public void destroySource(UUID sourceId) {
        frameQueues.remove(sourceId);
        positions.remove(sourceId);

        if (!isPlaying()) return;
        playbackThread.enqueueCommand(() -> playbackThread.destroySourceChannel(sourceId));
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
     * Submits a decoded 960-sample mono PCM frame for {@code sourceId}. Drops the oldest queued frame if that
     * source's queue is full, to keep playback latency bounded rather than growing unboundedly under sustained
     * overload. No-op if the source hasn't been registered via {@link #createSource(UUID, int)}.
     */
    public void submit(UUID sourceId, short[] frame) {
        BlockingQueue<short[]> queue = frameQueues.get(sourceId);
        if (queue == null) return;

        if (!queue.offer(frame)) {
            queue.poll();
            queue.offer(frame);
        }
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

    Map<UUID, double[]> positionsView() {
        return positions;
    }

    ListenerSnapshot currentListenerSnapshot() {
        return listenerSnapshot;
    }
}
