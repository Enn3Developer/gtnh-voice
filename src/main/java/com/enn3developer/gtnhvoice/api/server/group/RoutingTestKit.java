package com.enn3developer.gtnhvoice.api.server.group;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.api.server.ActivationIds;
import com.enn3developer.gtnhvoice.api.server.GtnhVoiceApi;
import com.enn3developer.gtnhvoice.api.server.IAudioFrame;
import com.enn3developer.gtnhvoice.api.server.IVoiceSession;
import com.enn3developer.gtnhvoice.api.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.api.server.SourceState;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;
import com.enn3developer.gtnhvoice.server.group.GroupManager;

// The formatter would reformat the javadoc example as Java and break its {@code} tag.
// @formatter:off
/**
 * Unit-test support for {@link IGroup#route}: routes one frame through your groups the way the server does, with api
 * types going in and plain values coming out, so an addon's routing tests import nothing outside the api packages.
 * No game, server or network is involved.
 *
 * <pre>{@code
 * RoutingTestKit kit = new RoutingTestKit();
 * IVoiceSession alice = kit.session(ALICE, "Alice");
 * IVoiceSession bob = kit.session(BOB, "Bob");
 * kit.sessionWithoutUdpAddress(CAROL, "Carol"); // handshake done, no datagram yet: sends to her are dropped
 * Map<UUID, PlayerSnapshot> positions = Map.of(
 *     ALICE, new PlayerSnapshot(ALICE, "Alice", 0, 64, 0, 0),
 *     BOB, new PlayerSnapshot(BOB, "Bob", 300, 64, 0, 0));
 *
 * List<RoutingTestKit.Delivery> sent = kit.route(
 *     alice, RoutingTestKit.frame(1, RADIO_KEY), positions, (player, group) -> true, radio, call);
 *
 * assertEquals(1, sent.size());
 * assertSame(bob, sent.get(0).getRecipient());
 * assertSame(radio, sent.get(0).getGroup());
 * }</pre>
 *
 * What matches the server: the groups route in the server's order, priority descending and ties by name, whatever
 * order you pass them in. They share one {@link RoutingContext} per frame, the class the server routes with, so
 * {@link RoutingContext#exclusive()} cuts the chain, each player gets at most one delivery per frame,
 * {@link RoutingContext#isServed} sees the earlier groups' deliveries, and {@link RoutingContext#sendTo} drops exactly
 * the sends the server drops. What differs: only the groups you pass route. The server ends every speaker's chain with
 * the built-in local (proximity) group; to include it, pass {@code GtnhVoiceApi.groupManager().byName("local")}, which
 * cuts off at {@link GtnhVoiceApi#proximityRange()}, 48 blocks under the default config. Sessions come in the order the
 * kit made them, where the server's order is unspecified.
 * <p>
 * Server-side test support, for plain JVM unit tests: compile against the api jar and run with the mod's dev jar on
 * the test runtime classpath, since the kit routes through the server's own code. Use a kit from one thread at a time:
 * it is not thread-safe, and {@link #route} runs every group's {@code route()} on the calling thread, which stands in
 * for the network thread.
 */
// @formatter:on
public final class RoutingTestKit {

    private final Map<UUID, VoiceServerSession> sessionsByPlayerUuid = new LinkedHashMap<>();
    private final Map<UUID, IVoiceSession> sessionsBySessionId = new LinkedHashMap<>();
    private int nextPort = 1;

    /** A kit with no sessions yet. Any thread. */
    public RoutingTestKit() {}

    /**
     * A live session for {@code playerUuid} named {@code playerName} that sends reach: it has a UDP address, as once
     * the player's client has sent its first datagram. Same thread rule as the kit.
     *
     * @throws IllegalArgumentException if this kit already has a session for {@code playerUuid}
     */
    public @NotNull IVoiceSession session(@NotNull UUID playerUuid, @NotNull String playerName) {
        VoiceServerSession session = add(playerUuid, playerName);
        session.touch(new InetSocketAddress(InetAddress.getLoopbackAddress(), nextPort++));
        return session;
    }

    /**
     * A live session for {@code playerUuid} named {@code playerName} with no UDP address yet, as between the voice
     * handshake and the client's first datagram: {@link IVoiceSession#hasUdpAddress()} is {@code false} and sends to
     * it are dropped. Same thread rule as the kit.
     *
     * @throws IllegalArgumentException if this kit already has a session for {@code playerUuid}
     */
    public @NotNull IVoiceSession sessionWithoutUdpAddress(@NotNull UUID playerUuid, @NotNull String playerName) {
        return add(playerUuid, playerName);
    }

    /**
     * An inbound frame for {@link #route}: sequence number {@code sequenceNumber}, sent under {@code activationId}
     * ({@link ActivationIds#DEFAULT} for ordinary voice, {@link ActivationIds#of} for a keyed client addon
     * activation), with a one-byte placeholder for the audio, which routing forwards without reading. Any thread; the
     * frame is immutable, and {@link IAudioFrame#getData()} returns a fresh copy of the placeholder each time.
     */
    public static @NotNull IAudioFrame frame(long sequenceNumber, @NotNull UUID activationId) {
        Objects.requireNonNull(activationId, "activationId");
        return new IAudioFrame() {

            @Override
            public long getSequenceNumber() {
                return sequenceNumber;
            }

            @Override
            public byte[] getData() {
                // Packets refuse an empty payload; real frames carry 1 to 2048 bytes of Opus.
                return new byte[1];
            }

            @Override
            public @NotNull UUID getActivationId() {
                return activationId;
            }
        };
    }

    /**
     * Routes {@code frame}, spoken by {@code speaker}, through {@code groups} in the server's priority order, and
     * returns what they sent. The groups see {@code positionSnapshot} as the tick's player positions,
     * {@code membershipTest} as group membership (what {@link RoutingContext#getSessionsForGroup} filters with), and
     * every session this kit made as the live sessions. Each call routes a fresh frame, so nobody starts out served.
     * Runs the groups on the calling thread; same thread rule as the kit.
     *
     * @param speaker          the speaking player: a session this kit made, with or without a UDP address
     * @param positionSnapshot player positions by player UUID; a player missing from it has no position
     * @param membershipTest   whether a player is in a group, as the server's group manager would answer
     * @param groups           one or more groups, in any order
     * @return every delivery, in the order the groups sent them; unmodifiable
     * @throws IllegalArgumentException if {@code speaker} is not a session of this kit, or {@code groups} is empty or
     *                                  names one group twice
     */
    public @NotNull List<Delivery> route(@NotNull IVoiceSession speaker, @NotNull IAudioFrame frame,
        @NotNull Map<UUID, PlayerSnapshot> positionSnapshot, @NotNull BiPredicate<UUID, IGroup> membershipTest,
        @NotNull IGroup... groups) {
        VoiceServerSession speakerSession = sessionsByPlayerUuid.get(speaker.getPlayerUuid());
        if (speakerSession != speaker) throw new IllegalArgumentException(
            "The speaker must be a session this kit made: " + speaker.getPlayerName());
        if (groups.length == 0) throw new IllegalArgumentException("Route through at least one group");

        // The wire id the kit stamps on each group's packets is the group's index here; deliveries map it back to the
        // group, as clients do with the server's group table.
        List<IGroup> groupsByWireId = new ArrayList<>(groups.length);
        Map<IGroup, Integer> wireIds = new IdentityHashMap<>();
        for (IGroup group : groups) {
            Objects.requireNonNull(group, "group");
            if (wireIds.putIfAbsent(group, groupsByWireId.size()) != null)
                throw new IllegalArgumentException("Group passed twice: " + group.getName());
            groupsByWireId.add(group);
        }
        List<IGroup> routingOrder = new ArrayList<>(groupsByWireId);
        routingOrder.sort(GroupManager.ROUTING_ORDER);

        List<Delivery> deliveries = new ArrayList<>();
        RoutingContext.builder()
            .packetSender((packet, sessionId, encryption, address) -> {
                SourceAudioPacket audio = (SourceAudioPacket) packet;
                deliveries.add(
                    new Delivery(
                        sessionsBySessionId.get(sessionId),
                        audio.getSourceState(),
                        audio.getX(),
                        audio.getY(),
                        audio.getZ(),
                        audio.getSourceId(),
                        groupsByWireId.get(audio.getGroupId())));
            })
            .positionSnapshot(positionSnapshot)
            .sessions(Collections.unmodifiableMap(sessionsByPlayerUuid))
            .speakerSession(speakerSession)
            .audio(frame)
            .group(routingOrder.get(0))
            .membershipTest(membershipTest)
            .groupIdResolver(wireIds::get)
            .buildDriver()
            .route(routingOrder);
        return Collections.unmodifiableList(deliveries);
    }

    private VoiceServerSession add(UUID playerUuid, String playerName) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(playerName, "playerName");
        if (sessionsByPlayerUuid.containsKey(playerUuid))
            throw new IllegalArgumentException("This kit already has a session for " + playerUuid);

        // The key is never used: deliveries are read off the packets before anything would encrypt them.
        VoiceServerSession session = new VoiceServerSession(
            playerUuid,
            playerName,
            UUID.randomUUID(),
            new AesEncryption(new byte[32]));
        sessionsByPlayerUuid.put(playerUuid, session);
        sessionsBySessionId.put(session.getSessionId(), session);
        return session;
    }

    /**
     * One packet a routed frame produced, as plain values: who receives it, how it plays (source state and the
     * position it plays from), whose voice it carries, and which group sent it. Immutable, so any thread may read it.
     * Two deliveries are equal when all their values are, comparing recipients and groups by identity.
     */
    public static final class Delivery {

        private final IVoiceSession recipient;
        private final byte sourceState;
        private final double x;
        private final double y;
        private final double z;
        private final UUID sourceId;
        private final IGroup group;

        Delivery(@NotNull IVoiceSession recipient, byte sourceState, double x, double y, double z,
            @NotNull UUID sourceId, @NotNull IGroup group) {
            this.recipient = recipient;
            this.sourceState = sourceState;
            this.x = x;
            this.y = y;
            this.z = z;
            this.sourceId = sourceId;
            this.group = group;
        }

        /** The session it goes to: the very object {@link RoutingTestKit#session} returned for that player. */
        public @NotNull IVoiceSession getRecipient() {
            return recipient;
        }

        /** How the recipient plays it: {@link SourceState#POSITIONAL} or {@link SourceState#FLAT}. */
        public byte getSourceState() {
            return sourceState;
        }

        /** The X coordinate it plays from, as the sending group passed it; a client ignores it for a flat delivery. */
        public double getX() {
            return x;
        }

        /** The Y coordinate it plays from; see {@link #getX()}. */
        public double getY() {
            return y;
        }

        /** The Z coordinate it plays from; see {@link #getX()}. */
        public double getZ() {
            return z;
        }

        /** Whose voice it carries: the speaker's player UUID, which clients use as the voice source id. */
        public @NotNull UUID getSourceId() {
            return sourceId;
        }

        /** The group whose send it was. */
        public @NotNull IGroup getGroup() {
            return group;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Delivery)) return false;

            Delivery that = (Delivery) other;
            return recipient == that.recipient && sourceState == that.sourceState
                && Double.compare(x, that.x) == 0
                && Double.compare(y, that.y) == 0
                && Double.compare(z, that.z) == 0
                && sourceId.equals(that.sourceId)
                && group == that.group;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                System.identityHashCode(recipient),
                sourceState,
                x,
                y,
                z,
                sourceId,
                System.identityHashCode(group));
        }

        @Override
        public String toString() {
            return "Delivery{to " + recipient.getPlayerName()
                + " via "
                + group.getName()
                + ", "
                + (sourceState == SourceState.FLAT ? "flat" : "positional at " + x + ", " + y + ", " + z)
                + ", voice of "
                + sourceId
                + "}";
        }
    }
}
