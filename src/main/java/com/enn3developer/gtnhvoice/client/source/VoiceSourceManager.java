package com.enn3developer.gtnhvoice.client.source;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.audio.codec.opus.OpusCodecSupplier;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceEndPacket;

/**
 * Owns every {@link VoiceSource} for the lifetime of one voice session, plus the single shared {@link
 * PlaybackManager} (one dedicated ALC device/context/thread, N positioned AL sources within it - see
 * {@link com.enn3developer.gtnhvoice.client.playback.PlaybackThread}).
 * <p>
 * Started/stopped by {@code VoiceClientManager} alongside the rest of the session. Routes decrypted
 * {@code SourceAudioPacket}/{@code SourceEndPacket} to the matching source, lazily creating one on first sight of a
 * sourceId, and runs the inactivity watchdog that resets (not destroys) sources that have gone quiet.
 */
public final class VoiceSourceManager {

    private static final long INACTIVITY_TIMEOUT_MILLIS = 250L;
    private static final long WATCHDOG_INTERVAL_MILLIS = 100L;
    private static final int MAX_SOURCES = 64;

    private final Map<UUID, VoiceSource> sources = new ConcurrentHashMap<>();
    // Which group's routing last won each source for this client (wire ids off SourceAudioPacket) - the HUD's
    // per-speaker group attribution. UDP receive thread writes, render thread reads.
    private final Map<UUID, Short> lastGroupIdBySource = new ConcurrentHashMap<>();
    private final PlaybackManager playbackManager;
    private final DecoderFactory decoderFactory;
    private final Function<UUID, Optional<String>> rosterLookup;

    private ScheduledExecutorService watchdog;
    private volatile boolean running;

    public VoiceSourceManager(Function<UUID, Optional<String>> rosterLookup) {
        this(new PlaybackManager(), OpusCodecSupplier::createDecoder, rosterLookup);
    }

    VoiceSourceManager(PlaybackManager playbackManager, DecoderFactory decoderFactory,
        Function<UUID, Optional<String>> rosterLookup) {
        this.playbackManager = playbackManager;
        this.decoderFactory = decoderFactory;
        this.rosterLookup = rosterLookup;
    }

    public void start() {
        if (running) return;
        running = true;

        playbackManager.start(Config.getOutputDeviceOrNull(), Config.getHrtfMode());

        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "gtnhvoice-source-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.scheduleAtFixedRate(
            this::checkInactivity,
            WATCHDOG_INTERVAL_MILLIS,
            WATCHDOG_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS);

        GtnhVoice.LOG.info("[VoiceSource] Manager started");
    }

    public void stop() {
        if (!running) return;
        running = false;

        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog = null;
        }

        for (VoiceSource source : sources.values()) {
            source.destroy();
        }
        sources.clear();
        lastGroupIdBySource.clear();

        playbackManager.stop();
        GtnhVoice.LOG.info("[VoiceSource] Manager stopped");
    }

    public void onSourceAudio(@NotNull SourceAudioPacket packet, int distance) {
        if (!running) return;

        UUID sourceId = packet.getSourceId();
        if (!sources.containsKey(sourceId)) {
            if (!rosterLookup.apply(sourceId)
                .isPresent()) return; // only create for a roster-present speaker
            if (sources.size() >= MAX_SOURCES) return; // hard cap against a source burst
        }

        VoiceSource source = sources.computeIfAbsent(sourceId, uuid -> running ? createSource(uuid, distance) : null);
        if (source == null) return;

        lastGroupIdBySource.put(sourceId, packet.getGroupId());
        source.handleAudio(
            packet.getSequenceNumber(),
            packet.getData(),
            packet.getX(),
            packet.getY(),
            packet.getZ(),
            packet.isPositional());
    }

    /**
     * The wire group id of the last audio frame received from {@code sourceId} - which group's routing won this
     * recipient (see {@code SourceAudioPacket#getGroupId}) - or the local built-in's 0 before any frame landed.
     * Read at render rate by the HUD; written per frame on the UDP receive thread.
     */
    public short lastGroupIdFor(@NotNull UUID sourceId) {
        Short groupId = lastGroupIdBySource.get(sourceId);
        return groupId == null ? 0 : groupId;
    }

    public void removeSource(UUID sourceId) {
        lastGroupIdBySource.remove(sourceId);
        VoiceSource source = sources.remove(sourceId);
        if (source != null) source.destroy();
    }

    public void onSourceEnd(@NotNull SourceEndPacket packet) {
        removeSource(packet.getSourceId());
    }

    /**
     * Publishes the local player's absolute position/look direction to the shared AL listener. Called every client
     * tick; safe to call even when no sources exist yet.
     */
    public void updateListener(double x, double y, double z, float lookX, float lookY, float lookZ) {
        playbackManager.updateListener(x, y, z, lookX, lookY, lookZ);
    }

    /**
     * The session's shared {@link PlaybackManager}, for {@code AudioDeviceController} to drive live output-device/
     * HRTF rebuilds against. Never {@code null} once constructed, but only meaningfully "playing" between
     * {@link #start()} and {@link #stop()}.
     */
    public PlaybackManager getPlaybackManager() {
        return playbackManager;
    }

    /**
     * Snapshot of sourceIds currently in an active speech segment, for the who's-talking HUD. Cheap and
     * read-only: reuses the inactivity-timeout state each {@link VoiceSource} already tracks rather than adding a
     * separate speaking-detection mechanism. Safe to call every render frame from the client thread while the
     * watchdog thread concurrently flips segment state.
     */
    public Set<UUID> getSpeakingSourceIds() {
        Set<UUID> speaking = new HashSet<>();
        for (Map.Entry<UUID, VoiceSource> entry : sources.entrySet()) {
            if (!entry.getValue()
                .isSegmentActive()) continue;

            speaking.add(entry.getKey());
        }
        return speaking;
    }

    private VoiceSource createSource(UUID sourceId, int distance) {
        VoiceSource source = new VoiceSource(sourceId, playbackManager, decoderFactory);
        try {
            source.create(distance);
        } catch (CodecException e) {
            GtnhVoice.LOG.error("[VoiceSource] Failed to open decoder for sourceId={}", sourceId, e);
            return null;
        }

        // One-shot per new source: proves the roster lookup actually answers the query the
        // who's-talking HUD will make, without logging on every audio frame.
        GtnhVoice.LOG.info(
            "[VoiceSource] New source sourceId={} resolved via roster to name={}",
            sourceId,
            rosterLookup.apply(sourceId)
                .orElse("<unknown>"));

        return source;
    }

    private void checkInactivity() {
        long now = System.currentTimeMillis();
        for (VoiceSource source : sources.values()) {
            source.checkInactivity(now, INACTIVITY_TIMEOUT_MILLIS);
        }
    }
}
