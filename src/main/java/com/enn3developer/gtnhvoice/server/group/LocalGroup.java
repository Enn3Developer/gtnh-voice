package com.enn3developer.gtnhvoice.server.group;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

/**
 * The default proximity group: same-dimension, distance-cutoff routing, always positional - the exact behavior
 * voice chat has always had. Every player is in this group unless {@link GroupManager} assigns them elsewhere.
 * <p>
 * Stateless: routing reads nothing beyond what {@link RoutingContext} hands it (see {@link IGroup#route} for the
 * threading contract). A speaker with no position snapshot yet is silently dropped - positional playback is
 * meaningless without one.
 */
public final class LocalGroup implements IGroup {

    /** The built-in {@link #getName} identity - the single source for every site that spells it out. */
    public static final String NAME = "local";

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDisplayName() {
        // Exactly the label the HUD always hardcoded, so the default group renders unchanged.
        return NAME;
    }

    @Override
    public void route(@NotNull VoiceServerSession speakerSession, @NotNull PlayerAudioPacket audio,
        @NotNull RoutingContext context) {
        UUID speakerUuid = speakerSession.getPlayerUuid();
        Map<UUID, PlayerSnapshot> snapshot = context.getPositionSnapshot();
        PlayerSnapshot speakerPos = snapshot.get(speakerUuid);
        if (speakerPos == null) return;

        int cutoff = Math.min(Config.distance, Config.maxDistance);

        for (VoiceServerSession recipientSession : context.getSessionsByPlayerUuid()
            .values()) {
            if (recipientSession.getPlayerUuid()
                .equals(speakerUuid)) continue;

            InetSocketAddress recipientAddress = recipientSession.getLastAddress();
            if (recipientAddress == null) continue;

            PlayerSnapshot recipientPos = snapshot.get(recipientSession.getPlayerUuid());
            if (recipientPos == null) continue;

            if (recipientPos.getDimensionId() != speakerPos.getDimensionId()) continue;

            if (speakerPos.distanceTo(recipientPos) > cutoff) continue;

            SourceAudioPacket forward = new SourceAudioPacket(
                audio.getSequenceNumber(),
                SourceAudioPacket.STATE_POSITIONAL,
                audio.getData(),
                speakerUuid,
                speakerPos.getX(),
                speakerPos.getY(),
                speakerPos.getZ());
            context.sendTo(recipientSession, forward);
        }
    }
}
