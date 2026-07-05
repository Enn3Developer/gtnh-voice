package com.enn3developer.gtnhvoice.api.server.group;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.api.server.IAudioFrame;
import com.enn3developer.gtnhvoice.api.server.IVoiceSession;
import com.enn3developer.gtnhvoice.api.server.PacketSender;
import com.enn3developer.gtnhvoice.api.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.api.server.SourceState;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

/**
 * Everything a group may touch while routing one audio frame, and nothing more: the routed event itself (speaker
 * session, inbound audio, the group being routed), the immutable per-tick position snapshot, a read-only
 * {@link IVoiceSession} view of the live sessions, and {@link #sendTo} as the only way to emit packets - groups
 * never see the UDP transport, the server manager, or the concrete sessions' credentials, only the
 * {@link PacketSender} seam behind {@link #sendTo}. Built by the server manager per routed frame, capturing the
 * snapshot reference current at that moment, and handed to {@link IGroup#route} on the UDP/Netty thread.
 * <p>
 * Most groups should route through the fluent {@link RecipientSelection} entry points
 * ({@link #getAllSessions()}/{@link #getSessionsForGroup()}); the raw session map and {@link #sendTo} stay
 * available for groups needing manual control.
 */
public final class RoutingContext {

    private final PacketSender transport;
    private final Map<UUID, PlayerSnapshot> positionSnapshot;
    private final Map<UUID, VoiceServerSession> sessionsByPlayerUuid;
    private final Map<UUID, IVoiceSession> sessionViewByPlayerUuid;
    private final VoiceServerSession speakerSession;
    private final IAudioFrame audio;
    private final IGroup group;
    private final Function<UUID, IGroup> membershipResolver;

    private RoutingContext(Builder builder) {
        this.transport = builder.require(builder.packetSender, "packetSender");
        this.positionSnapshot = builder.require(builder.positionSnapshot, "positionSnapshot");
        this.sessionsByPlayerUuid = builder.require(builder.sessions, "sessions");
        this.sessionViewByPlayerUuid = Collections.unmodifiableMap(this.sessionsByPlayerUuid);
        this.speakerSession = builder.require(builder.speakerSession, "speakerSession");
        this.audio = builder.require(builder.audio, "audio");
        this.group = builder.require(builder.group, "group");
        this.membershipResolver = builder.require(builder.membershipResolver, "membershipResolver");
    }

    /** A fresh {@link Builder}; see its javadoc for who builds contexts and when. */
    public static Builder builder() {
        return new Builder();
    }

    /** The session whose audio frame is being routed. */
    public IVoiceSession getSpeakerSession() {
        return speakerSession;
    }

    /** The inbound audio frame being routed. */
    public IAudioFrame getAudio() {
        return audio;
    }

    /** The position/dimension snapshot of every online player, as of the tick this frame arrived in. */
    public Map<UUID, PlayerSnapshot> getPositionSnapshot() {
        return positionSnapshot;
    }

    /** Read-only view of every live voice session, keyed by player UUID. */
    public Map<UUID, IVoiceSession> getSessionsByPlayerUuid() {
        return sessionViewByPlayerUuid;
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
    public Predicate<IVoiceSession> inDimension(int dimensionId) {
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
    public Predicate<IVoiceSession> withinDistanceOf(double x, double y, double z, int dimensionId, double radius) {
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
    Predicate<IVoiceSession> recipientSnapshotFilter(@NotNull Predicate<PlayerSnapshot> snapshotFilter) {
        return session -> {
            PlayerSnapshot recipientPos = positionSnapshot.get(session.getPlayerUuid());
            if (recipientPos == null) return false;

            return snapshotFilter.test(recipientPos);
        };
    }

    /**
     * Builds one outgoing audio packet from this context's frame (sequence, data) and speaker UUID, stamped with
     * {@code sourceState} (see {@link SourceState}) and the given source coordinates, and sends it to
     * {@code recipient} over the UDP transport, wrapped with the recipient's own secret/encryption/address. The
     * coordinates are free: pass the speaker's snapshot position for normal routing, or a fixed point to emit
     * from somewhere else (loudspeaker blocks, radio towers). The concrete session is resolved from this
     * context's live sessions by the recipient's player UUID; no-op if the recipient is unknown (a foreign
     * {@link IVoiceSession} implementation, or a session removed since the context was built) or has no UDP
     * address yet - never throws on the UDP/Netty thread over a stale recipient.
     */
    public void sendTo(@NotNull IVoiceSession recipient, byte sourceState, double x, double y, double z) {
        VoiceServerSession session = sessionsByPlayerUuid.get(recipient.getPlayerUuid());
        if (session == null) return;

        InetSocketAddress address = session.getLastAddress();
        if (address == null) return;

        SourceAudioPacket packet = new SourceAudioPacket(
            audio.getSequenceNumber(),
            sourceState,
            audio.getData(),
            speakerSession.getPlayerUuid(),
            x,
            y,
            z);
        transport.send(packet, session.getSecret(), session.getEncryption(), address);
    }

    /**
     * Builds a {@link RoutingContext}. Contexts are normally built by the mod per routed frame - the builder
     * exists chiefly for addon unit tests, paired with a capturing {@link PacketSender}. Every field is
     * mandatory; {@link #build()} throws {@link IllegalStateException} naming the first missing one.
     */
    public static final class Builder {

        private PacketSender packetSender;
        private Map<UUID, PlayerSnapshot> positionSnapshot;
        private Map<UUID, VoiceServerSession> sessions;
        private VoiceServerSession speakerSession;
        private IAudioFrame audio;
        private IGroup group;
        private Function<UUID, IGroup> membershipResolver;

        private Builder() {}

        /** The transport seam every {@link RoutingContext#sendTo} goes through - capture it in tests. */
        public Builder packetSender(@NotNull PacketSender packetSender) {
            this.packetSender = packetSender;
            return this;
        }

        /** The immutable per-tick position/dimension snapshot, keyed by player UUID. */
        public Builder positionSnapshot(@NotNull Map<UUID, PlayerSnapshot> positionSnapshot) {
            this.positionSnapshot = positionSnapshot;
            return this;
        }

        /** The live concrete sessions, keyed by player UUID - groups only ever see them as {@link IVoiceSession}. */
        public Builder sessions(@NotNull Map<UUID, VoiceServerSession> sessions) {
            this.sessions = sessions;
            return this;
        }

        /** The concrete session whose audio frame is being routed. */
        public Builder speakerSession(@NotNull VoiceServerSession speakerSession) {
            this.speakerSession = speakerSession;
            return this;
        }

        /** The inbound audio frame being routed. */
        public Builder audio(@NotNull IAudioFrame audio) {
            this.audio = audio;
            return this;
        }

        /** The group being routed - the one whose {@link IGroup#route} receives this context. */
        public Builder group(@NotNull IGroup group) {
            this.group = group;
            return this;
        }

        /**
         * Resolves any player's current group (wired to {@link IGroupManager#groupOf} by the server manager);
         * must be safe to call from the UDP/Netty thread.
         */
        public Builder membershipResolver(@NotNull Function<UUID, IGroup> membershipResolver) {
            this.membershipResolver = membershipResolver;
            return this;
        }

        /** @throws IllegalStateException naming the first field left unset */
        public RoutingContext build() {
            return new RoutingContext(this);
        }

        private <T> T require(T value, String fieldName) {
            if (value == null) throw new IllegalStateException("RoutingContext.Builder: " + fieldName + " was not set");

            return value;
        }
    }
}
