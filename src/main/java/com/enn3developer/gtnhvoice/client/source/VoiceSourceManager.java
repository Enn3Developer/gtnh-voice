package com.enn3developer.gtnhvoice.client.source;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
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

    private final Map<UUID, VoiceSource> sources = new ConcurrentHashMap<>();
    private final PlaybackManager playbackManager = new PlaybackManager();

    private ScheduledExecutorService watchdog;
    private volatile boolean running;

    public void start() {
        if (running) return;
        running = true;

        playbackManager.start();

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

        playbackManager.stop();
        GtnhVoice.LOG.info("[VoiceSource] Manager stopped");
    }

    public void onSourceAudio(@NotNull SourceAudioPacket packet, int distance) {
        VoiceSource source = sources.computeIfAbsent(packet.getSourceId(), uuid -> createSource(uuid, distance));
        if (source == null) return; // creation failed, already logged

        source.handleAudio(packet.getSequenceNumber(), packet.getData(), packet.getX(), packet.getY(), packet.getZ());
    }

    public void onSourceEnd(@NotNull SourceEndPacket packet) {
        VoiceSource source = sources.remove(packet.getSourceId());
        if (source != null) source.destroy();
    }

    /**
     * Publishes the local player's absolute position/look direction to the shared AL listener. Called every client
     * tick; safe to call even when no sources exist yet.
     */
    public void updateListener(double x, double y, double z, float lookX, float lookY, float lookZ) {
        playbackManager.updateListener(x, y, z, lookX, lookY, lookZ);
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
            if (entry.getValue()
                .isSegmentActive()) {
                speaking.add(entry.getKey());
            }
        }
        return speaking;
    }

    private VoiceSource createSource(UUID sourceId, int distance) {
        VoiceSource source = new VoiceSource(sourceId, playbackManager);
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
            VoiceClientManager.getInstance()
                .resolveName(sourceId)
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
