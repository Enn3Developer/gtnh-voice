package com.enn3developer.gtnhvoice.api.server.group;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

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
    private final BiPredicate<UUID, IGroup> membershipTest;
    private final ToIntFunction<IGroup> groupIdResolver;

    // Multi-group routing state: the group currently being routed (swapped by the server manager before each
    // route() call in priority order) and the recipients already served this frame - sendTo claims recipients
    // first-come, which with priority-ordered routing IS the dedup: one packet per recipient per frame, from
    // their highest-priority group.
    private IGroup group;
    private final Set<UUID> servedRecipients = new HashSet<>();
    private boolean exclusive;

    private RoutingContext(Builder builder) {
        this.transport = builder.require(builder.packetSender, "packetSender");
        this.positionSnapshot = builder.require(builder.positionSnapshot, "positionSnapshot");
        this.sessionsByPlayerUuid = builder.require(builder.sessions, "sessions");
        this.sessionViewByPlayerUuid = Collections.unmodifiableMap(this.sessionsByPlayerUuid);
        this.speakerSession = builder.require(builder.speakerSession, "speakerSession");
        this.audio = builder.require(builder.audio, "audio");
        this.group = builder.require(builder.group, "group");
        this.membershipTest = builder.require(builder.membershipTest, "membershipTest");
        this.groupIdResolver = builder.require(builder.groupIdResolver, "groupIdResolver");
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
     * A fresh {@link RecipientSelection} over the sessions whose player is currently a member of the group
     * being routed (via the membership test; every player counts as a member of the implicit local built-in).
     * Single-use, only valid inside the {@link IGroup#route} call this context was built for.
     */
    public RecipientSelection getSessionsForGroup() {
        return new RecipientSelection(this).filter(session -> membershipTest.test(session.getPlayerUuid(), group));
    }

    /**
     * Claims the rest of this frame for the group currently routing: every lower-priority group of the speaker
     * (the implicit local fallthrough included) is skipped once this {@link IGroup#route} call returns. For
     * groups that must suppress all other routing while active - an isolation cell, a stage-only broadcast, a
     * radio channel that mutes proximity. Frame-scoped and irreversible for the frame: the next inbound frame
     * routes the full priority chain again unless the group calls this again.
     */
    public void exclusive() {
        this.exclusive = true;
    }

    /**
     * The frame-routing driver: walks a speaker's groups in the given (already priority-sorted) order over the
     * one shared context, swapping the routed group between calls and honoring {@link RoutingContext#exclusive}
     * claims. Deliberately NOT part of the group-facing surface - groups receive only the
     * {@link RoutingContext}, so they can neither re-attribute their sends to another group nor read the
     * exclusivity state; the driver exists solely for the server manager (and addon tests exercising a full
     * chain), obtained via {@link Builder#buildDriver()}.
     */
    public static final class Driver {

        private final RoutingContext context;

        private Driver(RoutingContext context) {
            this.context = context;
        }

        /** The driven context - what tests hand to a single group's {@code route()} directly. */
        public RoutingContext context() {
            return context;
        }

        /**
         * Routes the frame through {@code groups} in list order (callers pass the pre-sorted membership
         * snapshot). A group that called {@link RoutingContext#exclusive} cuts the chain: the remaining
         * groups, local fallthrough included, never route this frame.
         */
        public void route(@NotNull List<IGroup> groups) {
            for (IGroup group : groups) {
                context.group = group;
                group.route(context);
                if (context.exclusive) break;
            }
        }
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
        double radiusSq = radius * radius;
        return recipientSnapshotFilter(recipientPos -> {
            if (recipientPos.getDimensionId() != dimensionId) return false;

            double dx = recipientPos.getX() - x;
            double dy = recipientPos.getY() - y;
            double dz = recipientPos.getZ() - z;
            return dx * dx + dy * dy + dz * dz <= radiusSq;
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

        // The multi-group dedup: first group to claim a recipient this frame wins - and since the manager
        // routes groups in priority order, "first" IS "highest priority". Later groups' sends are dropped
        // silently, so a recipient never hears the same frame twice.
        if (!servedRecipients.add(recipient.getPlayerUuid())) return;

        SourceAudioPacket packet = new SourceAudioPacket(
            audio.getSequenceNumber(),
            sourceState,
            audio.getData(),
            speakerSession.getPlayerUuid(),
            x,
            y,
            z,
            (short) groupIdResolver.applyAsInt(group));
        transport.send(packet, session.getSessionId(), session.getEncryption(), address);
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
        private BiPredicate<UUID, IGroup> membershipTest;
        private ToIntFunction<IGroup> groupIdResolver;

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

        /**
         * The group initially considered "being routed" - what {@link RoutingContext#getSessionsForGroup} and
         * sent packets attribute to until a {@link Driver} swaps in the next one. Single-group addon tests set
         * the group under test here and call its {@code route()} directly.
         */
        public Builder group(@NotNull IGroup group) {
            this.group = group;
            return this;
        }

        /**
         * Tests whether a player is currently a member of a group (wired to the group manager's membership
         * index by the server manager; the implicit local built-in counts everyone as a member); must be safe
         * to call from the UDP/Netty thread.
         */
        public Builder membershipTest(@NotNull BiPredicate<UUID, IGroup> membershipTest) {
            this.membershipTest = membershipTest;
            return this;
        }

        /**
         * Resolves a group's wire id, stamped into every outgoing audio packet so clients can attribute what
         * they hear ({@code GroupManager.groupIdOf} in production; return a constant in tests).
         */
        public Builder groupIdResolver(@NotNull ToIntFunction<IGroup> groupIdResolver) {
            this.groupIdResolver = groupIdResolver;
            return this;
        }

        /** @throws IllegalStateException naming the first field left unset */
        public RoutingContext build() {
            return new RoutingContext(this);
        }

        /**
         * Builds the context wrapped in its {@link Driver} - the multi-group entry point the server manager
         * uses per frame. @throws IllegalStateException naming the first field left unset
         */
        public Driver buildDriver() {
            return new Driver(build());
        }

        private <T> T require(T value, String fieldName) {
            if (value == null) throw new IllegalStateException("RoutingContext.Builder: " + fieldName + " was not set");

            return value;
        }
    }
}
