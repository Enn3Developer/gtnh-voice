package com.enn3developer.gtnhvoice.server.group;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

/**
 * Server-admin announcement group: broadcasts a speaker to EVERY connected voice client - no dimension check, no
 * distance cutoff, no recipient-snapshot requirement - as flat/full-gain audio ({@link SourceAudioPacket#FLAG_FLAT}).
 * The first sender of that flag; {@link LocalGroup} always stays positional.
 * <p>
 * Unlike {@link LocalGroup}, a speaker with no position snapshot yet is NOT dropped: flat playback ignores
 * position entirely, and announcements must work the instant an admin joins, so the packet just carries
 * (0, 0, 0). The only state held here is per-speaker log-throttle bookkeeping (see {@link IGroup#route} for the
 * threading contract).
 */
public final class GlobalGroup implements IGroup {

    private static final long ROUTING_LOG_THROTTLE_MILLIS = 500;

    private final SpeakerLogThrottle routingLog = new SpeakerLogThrottle(ROUTING_LOG_THROTTLE_MILLIS);

    @Override
    public @NotNull String getName() {
        return "global";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "global";
    }

    @Override
    public void route(@NotNull VoiceServerSession speakerSession, @NotNull PlayerAudioPacket audio,
        @NotNull RoutingContext context) {
        UUID speakerUuid = speakerSession.getPlayerUuid();
        PlayerSnapshot speakerPos = context.getPositionSnapshot()
            .get(speakerUuid);
        double x = speakerPos == null ? 0 : speakerPos.getX();
        double y = speakerPos == null ? 0 : speakerPos.getY();
        double z = speakerPos == null ? 0 : speakerPos.getZ();

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

            SourceAudioPacket forward = new SourceAudioPacket(
                audio.getSequenceNumber(),
                SourceAudioPacket.FLAG_FLAT,
                audio.getData(),
                speakerUuid,
                x,
                y,
                z);
            context.sendTo(recipientSession, forward);
            recipients.add(name);
        }

        logRoutingThrottled(speakerSession, recipients, excluded);
    }

    @Override
    public void onPlayerRemoved(@NotNull UUID playerUuid) {
        routingLog.onPlayerRemoved(playerUuid);
    }

    @Override
    public void clear() {
        routingLog.clear();
    }

    private void logRoutingThrottled(@NotNull VoiceServerSession speakerSession, @NotNull List<String> recipients,
        @NotNull List<String> excluded) {
        if (!routingLog.shouldLog(speakerSession.getPlayerUuid())) return;

        GtnhVoice.LOG.info(
            "routing (global) from {} -> recipients [{}] excluded [{}]",
            speakerSession.getPlayerName(),
            String.join(", ", recipients),
            String.join(", ", excluded));
    }
}
