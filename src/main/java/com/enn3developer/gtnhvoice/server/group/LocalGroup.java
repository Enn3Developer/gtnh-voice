package com.enn3developer.gtnhvoice.server.group;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

/**
 * The default proximity group: same-dimension, distance-cutoff routing, always positional - the exact behavior
 * voice chat has always had. Every player is in this group unless {@link GroupManager} assigns them elsewhere.
 * <p>
 * The only state held here is per-speaker log-throttle bookkeeping; routing itself reads nothing beyond what
 * {@link RoutingContext} hands it (see {@link IGroup#route} for the threading contract).
 */
public final class LocalGroup implements IGroup {

    private static final long ROUTING_LOG_THROTTLE_MILLIS = 500;
    private static final long NO_SNAPSHOT_LOG_THROTTLE_MILLIS = 5000;

    private final Map<UUID, AtomicLong> lastRoutingLogMillis = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> lastNoSnapshotLogMillis = new ConcurrentHashMap<>();

    @Override
    public @NotNull String getName() {
        return "local";
    }

    @Override
    public @NotNull String getDisplayName() {
        // Exactly the label the HUD always hardcoded, so the default group renders unchanged.
        return "local";
    }

    @Override
    public void route(@NotNull VoiceServerSession speakerSession, @NotNull PlayerAudioPacket audio,
        @NotNull RoutingContext context) {
        UUID speakerUuid = speakerSession.getPlayerUuid();
        Map<UUID, PlayerSnapshot> snapshot = context.getPositionSnapshot();
        PlayerSnapshot speakerPos = snapshot.get(speakerUuid);
        if (speakerPos == null) {
            logNoSnapshotThrottled(speakerSession);
            return;
        }

        int cutoff = Math.min(Config.distance, Config.maxDistance);
        boolean maxDistanceIsBinding = Config.maxDistance < Config.distance;

        List<String> recipients = new ArrayList<>();
        List<String> excluded = new ArrayList<>();

        for (VoiceServerSession recipientSession : context.getSessionsByPlayerUuid()
            .values()) {
            String name = recipientSession.getPlayerName();

            if (recipientSession.getPlayerUuid()
                .equals(speakerUuid)) {
                excluded.add(name + "(self)");
                continue;
            }

            InetSocketAddress recipientAddress = recipientSession.getLastAddress();
            if (recipientAddress == null) {
                excluded.add(name + "(no-udp)");
                continue;
            }

            PlayerSnapshot recipientPos = snapshot.get(recipientSession.getPlayerUuid());
            if (recipientPos == null) {
                excluded.add(name + "(no-snapshot)");
                continue;
            }

            if (recipientPos.getDimensionId() != speakerPos.getDimensionId()) {
                excluded.add(name + "(other-dim=" + recipientPos.getDimensionId() + ")");
                continue;
            }

            double distance = speakerPos.distanceTo(recipientPos);
            if (distance > cutoff) {
                excluded.add(
                    String.format(
                        "%s(out-of-range:%.1fm>%s cutoff %dm)",
                        name,
                        distance,
                        maxDistanceIsBinding ? "maxDistance" : "distance",
                        cutoff));
                continue;
            }

            SourceAudioPacket forward = new SourceAudioPacket(
                audio.getSequenceNumber(),
                SourceAudioPacket.STATE_POSITIONAL,
                audio.getData(),
                speakerUuid,
                speakerPos.getX(),
                speakerPos.getY(),
                speakerPos.getZ());
            context.sendTo(recipientSession, forward);
            recipients.add(String.format("%s@%.1fm", name, distance));
        }

        logRoutingThrottled(speakerSession, speakerPos, recipients, excluded);
    }

    @Override
    public void onPlayerRemoved(@NotNull UUID playerUuid) {
        lastRoutingLogMillis.remove(playerUuid);
        lastNoSnapshotLogMillis.remove(playerUuid);
    }

    @Override
    public void clear() {
        lastRoutingLogMillis.clear();
        lastNoSnapshotLogMillis.clear();
    }

    private void logRoutingThrottled(@NotNull VoiceServerSession speakerSession, @NotNull PlayerSnapshot speakerPos,
        @NotNull List<String> recipients, @NotNull List<String> excluded) {
        AtomicLong last = lastRoutingLogMillis.computeIfAbsent(speakerSession.getPlayerUuid(), id -> new AtomicLong());
        if (!LogThrottle.shouldLog(last, ROUTING_LOG_THROTTLE_MILLIS)) return;

        GtnhVoice.LOG.info(
            "routing from {} pos({}, {}, {}) dim={} -> recipients [{}] excluded [{}]",
            speakerSession.getPlayerName(),
            String.format("%.1f", speakerPos.getX()),
            String.format("%.1f", speakerPos.getY()),
            String.format("%.1f", speakerPos.getZ()),
            speakerPos.getDimensionId(),
            String.join(", ", recipients),
            String.join(", ", excluded));
    }

    private void logNoSnapshotThrottled(@NotNull VoiceServerSession speakerSession) {
        AtomicLong last = lastNoSnapshotLogMillis
            .computeIfAbsent(speakerSession.getPlayerUuid(), id -> new AtomicLong());
        if (!LogThrottle.shouldLog(last, NO_SNAPSHOT_LOG_THROTTLE_MILLIS)) return;

        GtnhVoice.LOG.warn(
            "Dropped audio frame from {}: no position snapshot yet (just joined?)",
            speakerSession.getPlayerName());
    }
}
