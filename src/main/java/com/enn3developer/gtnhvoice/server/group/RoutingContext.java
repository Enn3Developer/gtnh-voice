package com.enn3developer.gtnhvoice.server.group;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

/**
 * Everything a group may touch while routing one audio frame, and nothing more: the routed event itself (speaker
 * session, inbound audio, the group being routed), the immutable per-tick position snapshot, a read-only view of
 * the live sessions, and {@link #sendTo} as the only way to emit packets - groups never see the UDP transport or
 * the server manager directly, only the {@link PacketSender} seam. Built by the server manager per routed frame,
 * capturing the snapshot reference current at that moment, and handed to {@link IGroup#route} on the UDP/Netty
 * thread.
 * <p>
 * Most groups should route through the fluent {@link RecipientSelection} entry points
 * ({@link #getAllSessions()}/{@link #getSessionsForGroup()}); the raw session map and {@link #sendTo} stay
 * available for groups needing manual control.
 */
public final class RoutingContext {

    private final PacketSender transport;
    private final Map<UUID, PlayerSnapshot> positionSnapshot;
    private final Map<UUID, VoiceServerSession> sessionsByPlayerUuid;
    private final VoiceServerSession speakerSession;
    private final PlayerAudioPacket audio;
    private final IGroup group;
    private final Function<UUID, IGroup> membershipResolver;

    /**
     * @param group              the group being routed - the one whose {@link IGroup#route} receives this context
     * @param membershipResolver resolves any player's current group (wired to {@code GroupManager::groupOf} by the
     *                           server manager); must be safe to call from the UDP/Netty thread
     */
    public RoutingContext(@NotNull PacketSender transport, @NotNull Map<UUID, PlayerSnapshot> positionSnapshot,
        @NotNull Map<UUID, VoiceServerSession> sessionsByPlayerUuid, @NotNull VoiceServerSession speakerSession,
        @NotNull PlayerAudioPacket audio, @NotNull IGroup group, @NotNull Function<UUID, IGroup> membershipResolver) {
        this.transport = transport;
        this.positionSnapshot = positionSnapshot;
        this.sessionsByPlayerUuid = sessionsByPlayerUuid;
        this.speakerSession = speakerSession;
        this.audio = audio;
        this.group = group;
        this.membershipResolver = membershipResolver;
    }

    /** The session whose audio frame is being routed. */
    public VoiceServerSession getSpeakerSession() {
        return speakerSession;
    }

    /** The inbound audio frame being routed. */
    public PlayerAudioPacket getAudio() {
        return audio;
    }

    /** The position/dimension snapshot of every online player, as of the tick this frame arrived in. */
    public Map<UUID, PlayerSnapshot> getPositionSnapshot() {
        return positionSnapshot;
    }

    /** Read-only view of every live voice session, keyed by player UUID. */
    public Map<UUID, VoiceServerSession> getSessionsByPlayerUuid() {
        return sessionsByPlayerUuid;
    }

    /**
     * A fresh {@link RecipientSelection} over every live session. Single-use, only valid inside the
     * {@link IGroup#route} call this context was built for.
     */
    public RecipientSelection getAllSessions() {
        return new RecipientSelection(this);
    }

    /**
     * A fresh {@link RecipientSelection} over the sessions whose player currently belongs to the group being
     * routed (identity comparison via the membership resolver). Works for the local built-in too: unassigned
     * players resolve to the same shared {@code LocalGroup} instance the manager routes. Single-use, only valid
     * inside the {@link IGroup#route} call this context was built for.
     */
    public RecipientSelection getSessionsForGroup() {
        return new RecipientSelection(this)
            .filter(session -> membershipResolver.apply(session.getPlayerUuid()) == group);
    }

    /**
     * Predicate keeping recipients whose position snapshot exists and is in {@code dimensionId} - the absolute
     * (speaker-independent) dimension filter, for {@link RecipientSelection#filter}. Recipients without a
     * snapshot are dropped. Evaluates against this context's immutable per-tick snapshot, so it is safe on the
     * UDP/Netty thread.
     */
    public Predicate<VoiceServerSession> inDimension(int dimensionId) {
        return recipientSnapshotFilter(recipientPos -> recipientPos.getDimensionId() == dimensionId);
    }

    /**
     * Predicate keeping recipients whose position snapshot exists, is in {@code dimensionId}, and is within
     * {@code radius} blocks of the fixed point (exactly-at-radius kept, consistent with
     * {@link RecipientSelection#cutoffDistance}) - the fixed-point variant of the speaker-centric
     * {@code cutoffDistance()}, for radio towers, loudspeaker blocks, or zones. Recipients without a snapshot
     * are dropped. Evaluates against this context's immutable per-tick snapshot, so it is safe on the UDP/Netty
     * thread.
     */
    public Predicate<VoiceServerSession> withinDistanceOf(double x, double y, double z, int dimensionId,
        double radius) {
        return recipientSnapshotFilter(recipientPos -> {
            if (recipientPos.getDimensionId() != dimensionId) return false;

            double dx = recipientPos.getX() - x;
            double dy = recipientPos.getY() - y;
            double dz = recipientPos.getZ() - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz) <= radius;
        });
    }

    /**
     * Lifts a snapshot predicate to a session predicate: resolves the recipient's position snapshot and drops
     * recipients without one. Shared plumbing for the factories above and {@link RecipientSelection}'s
     * positional filters - says nothing about the speaker.
     */
    Predicate<VoiceServerSession> recipientSnapshotFilter(@NotNull Predicate<PlayerSnapshot> snapshotFilter) {
        return session -> {
            PlayerSnapshot recipientPos = positionSnapshot.get(session.getPlayerUuid());
            if (recipientPos == null) return false;

            return snapshotFilter.test(recipientPos);
        };
    }

    /**
     * Sends {@code packet} to {@code recipient} over the UDP transport, wrapped with the recipient's own
     * secret/encryption/address. No-op if the recipient has no UDP address yet.
     */
    public void sendTo(@NotNull VoiceServerSession recipient, @NotNull SourceAudioPacket packet) {
        InetSocketAddress address = recipient.getLastAddress();
        if (address == null) return;

        transport.send(packet, recipient.getSecret(), recipient.getEncryption(), address);
    }
}
