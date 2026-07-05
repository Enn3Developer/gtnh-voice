package com.enn3developer.gtnhvoice.server.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.core.api.encryption.Encryption;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.network.VoiceProtocol;
import com.enn3developer.gtnhvoice.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

/**
 * Exercises {@link RecipientSelection}'s filters one at a time against a capturing {@link PacketSender} - each
 * test isolates one filter (or the terminal {@link RecipientSelection#send}) so the built-in groups' chains are
 * covered piecewise, plus the {@link RoutingContext#getSessionsForGroup} membership entry point.
 */
class RecipientSelectionTest {

    /** Marker group with no behavior - membership tests only need distinct identities. */
    private static final class NamedGroup implements IGroup {

        private final String name;

        private NamedGroup(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDisplayName() {
            return name;
        }

        @Override
        public void route(RoutingContext context) {}
    }

    private final IGroup routedGroup = new NamedGroup("routed");
    private final List<CapturedSend> sent = new ArrayList<>();
    private final Map<UUID, VoiceServerSession> sessions = new HashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshot = new HashMap<>();
    private final Map<UUID, IGroup> memberships = new HashMap<>();
    private int nextPort = 42_000;

    @Test
    void excludeSelfDropsOnlyTheSpeaker() {
        VoiceServerSession speaker = addSession("speaker", true);
        VoiceServerSession other = addSession("other", true);

        context(speaker).getAllSessions()
            .excludeSelf()
            .send(SourceAudioPacket.STATE_POSITIONAL);

        assertEquals(Collections.singleton(other.getLastAddress()), sentAddresses());
    }

    @Test
    void excludeNoAddressDropsSessionsWithoutUdp() {
        VoiceServerSession speaker = addSession("speaker", true);
        VoiceServerSession noUdp = addSession("noUdp", false);
        VoiceServerSession withUdp = addSession("withUdp", true);

        Set<UUID> passed = new HashSet<>();
        context(speaker).getAllSessions()
            .excludeSelf()
            .excludeNoAddress()
            .filter(session -> passed.add(session.getPlayerUuid()))
            .send(SourceAudioPacket.STATE_POSITIONAL);

        assertEquals(Collections.singleton(withUdp.getPlayerUuid()), passed);
        assertFalse(passed.contains(noUdp.getPlayerUuid()));
        assertEquals(Collections.singleton(withUdp.getLastAddress()), sentAddresses());
    }

    @Test
    void sameDimensionDropsOtherDimensionsAndSnapshotlessRecipients() {
        VoiceServerSession speaker = addSession("speaker", true);
        addSnapshot(speaker, 0, 64, 0, 0);
        VoiceServerSession sameDim = addSession("sameDim", true);
        addSnapshot(sameDim, 500, 64, 0, 0);
        VoiceServerSession otherDim = addSession("otherDim", true);
        addSnapshot(otherDim, 0, 64, 0, 1);
        addSession("noSnapshot", true);

        context(speaker).getAllSessions()
            .excludeSelf()
            .sameDimension()
            .send(SourceAudioPacket.STATE_POSITIONAL);

        assertEquals(Collections.singleton(sameDim.getLastAddress()), sentAddresses());
    }

    @Test
    void sameDimensionWithoutSpeakerSnapshotExcludesEveryone() {
        VoiceServerSession speaker = addSession("speaker", true);
        VoiceServerSession listener = addSession("listener", true);
        addSnapshot(listener, 0, 64, 0, 0);

        context(speaker).getAllSessions()
            .excludeSelf()
            .sameDimension()
            .send(SourceAudioPacket.STATE_POSITIONAL);

        assertTrue(sent.isEmpty());
    }

    @Test
    void cutoffDistanceKeepsExactlyAtCutoffAndDropsBeyond() {
        VoiceServerSession speaker = addSession("speaker", true);
        addSnapshot(speaker, 0, 64, 0, 0);
        VoiceServerSession atCutoff = addSession("atCutoff", true);
        addSnapshot(atCutoff, 48, 64, 0, 0);
        VoiceServerSession beyondCutoff = addSession("beyondCutoff", true);
        addSnapshot(beyondCutoff, 48.001, 64, 0, 0);
        addSession("noSnapshot", true);

        context(speaker).getAllSessions()
            .excludeSelf()
            .cutoffDistance(48)
            .send(SourceAudioPacket.STATE_POSITIONAL);

        assertEquals(Collections.singleton(atCutoff.getLastAddress()), sentAddresses());
    }

    @Test
    void cutoffDistanceWithoutSpeakerSnapshotExcludesEveryone() {
        VoiceServerSession speaker = addSession("speaker", true);
        VoiceServerSession listener = addSession("listener", true);
        addSnapshot(listener, 0, 64, 0, 0);

        context(speaker).getAllSessions()
            .excludeSelf()
            .cutoffDistance(48)
            .send(SourceAudioPacket.STATE_POSITIONAL);

        assertTrue(sent.isEmpty());
    }

    @Test
    void filterEscapeHatchAppliesCustomPredicate() {
        VoiceServerSession speaker = addSession("speaker", true);
        VoiceServerSession kept = addSession("kept", true);
        addSession("dropped", true);

        context(speaker).getAllSessions()
            .excludeSelf()
            .filter(
                session -> session.getPlayerName()
                    .equals("kept"))
            .send(SourceAudioPacket.STATE_POSITIONAL);

        assertEquals(Collections.singleton(kept.getLastAddress()), sentAddresses());
    }

    @Test
    void sessionsForGroupSelectsOnlyMembersOfTheRoutedGroup() {
        VoiceServerSession speaker = addSession("speaker", true);
        memberships.put(speaker.getPlayerUuid(), routedGroup);
        VoiceServerSession member = addSession("member", true);
        memberships.put(member.getPlayerUuid(), routedGroup);
        VoiceServerSession outsider = addSession("outsider", true);
        memberships.put(outsider.getPlayerUuid(), new NamedGroup("elsewhere"));

        context(speaker).getSessionsForGroup()
            .excludeSelf()
            .send(SourceAudioPacket.STATE_POSITIONAL);

        assertEquals(Collections.singleton(member.getLastAddress()), sentAddresses());
    }

    @Test
    void sessionsForGroupResolvesUnassignedPlayersToTheLocalDefault() {
        // Mirrors GroupManager: unassigned players resolve to the shared local instance, so routing the local
        // built-in selects them via the same identity comparison as explicit members.
        LocalGroup localGroup = new LocalGroup();
        VoiceServerSession speaker = addSession("speaker", true);
        VoiceServerSession unassigned = addSession("unassigned", true);
        VoiceServerSession elsewhere = addSession("elsewhere", true);
        memberships.put(elsewhere.getPlayerUuid(), new NamedGroup("elsewhere"));

        PlayerAudioPacket audio = audio(1L, new byte[] { 9 });
        RoutingContext context = new RoutingContext(
            capturingSender(),
            snapshot,
            Collections.unmodifiableMap(sessions),
            speaker,
            audio,
            localGroup,
            playerUuid -> memberships.getOrDefault(playerUuid, localGroup));
        context.getSessionsForGroup()
            .excludeSelf()
            .send(SourceAudioPacket.STATE_POSITIONAL);

        assertEquals(Collections.singleton(unassigned.getLastAddress()), sentAddresses());
    }

    @Test
    void sendWithoutSpeakerSnapshotUsesZeroCoordinates() {
        VoiceServerSession speaker = addSession("speaker", true);
        addSession("listener", true);

        context(speaker).getAllSessions()
            .excludeSelf()
            .send(SourceAudioPacket.FLAG_FLAT);

        assertEquals(1, sent.size());
        SourceAudioPacket forwarded = assertInstanceOf(SourceAudioPacket.class, sent.get(0).packet);
        assertEquals(0, forwarded.getX());
        assertEquals(0, forwarded.getY());
        assertEquals(0, forwarded.getZ());
    }

    @Test
    void sendStampsTheGivenSourceStateAndSpeakerCoordinates() {
        VoiceServerSession speaker = addSession("speaker", true);
        addSnapshot(speaker, 3, 64, -7, 0);
        addSession("listener", true);

        context(speaker).getAllSessions()
            .excludeSelf()
            .send(SourceAudioPacket.FLAG_FLAT);

        assertEquals(1, sent.size());
        SourceAudioPacket forwarded = assertInstanceOf(SourceAudioPacket.class, sent.get(0).packet);
        assertEquals(SourceAudioPacket.FLAG_FLAT, forwarded.getSourceState());
        assertEquals(speaker.getPlayerUuid(), forwarded.getSourceId());
        assertEquals(7L, forwarded.getSequenceNumber());
        assertEquals(3, forwarded.getX());
        assertEquals(64, forwarded.getY());
        assertEquals(-7, forwarded.getZ());
    }

    @Test
    void secondSendOnTheSameSelectionThrows() {
        VoiceServerSession speaker = addSession("speaker", true);
        VoiceServerSession listener = addSession("listener", true);

        RecipientSelection selection = context(speaker).getAllSessions()
            .excludeSelf();
        selection.send(SourceAudioPacket.STATE_POSITIONAL);

        assertThrows(IllegalStateException.class, () -> selection.send(SourceAudioPacket.STATE_POSITIONAL));
        // The first send still went through exactly once - no duplicate packets reached the wire.
        assertEquals(Collections.singleton(listener.getLastAddress()), sentAddresses());
        assertEquals(1, sent.size());
    }

    @Test
    void filterAfterSendThrows() {
        VoiceServerSession speaker = addSession("speaker", true);
        addSession("listener", true);

        RecipientSelection selection = context(speaker).getAllSessions()
            .excludeSelf();
        selection.send(SourceAudioPacket.STATE_POSITIONAL);

        assertThrows(IllegalStateException.class, selection::excludeSelf);
        assertThrows(IllegalStateException.class, selection::excludeNoAddress);
        assertThrows(IllegalStateException.class, selection::sameDimension);
        assertThrows(IllegalStateException.class, () -> selection.cutoffDistance(48));
        assertThrows(IllegalStateException.class, () -> selection.filter(session -> true));
    }

    private RoutingContext context(VoiceServerSession speaker) {
        return new RoutingContext(
            capturingSender(),
            snapshot,
            Collections.unmodifiableMap(sessions),
            speaker,
            audio(7L, new byte[] { 1, 2, 3 }),
            routedGroup,
            playerUuid -> memberships.getOrDefault(playerUuid, routedGroup));
    }

    private PacketSender capturingSender() {
        return (packet, secret, encryption, recipient) -> sent
            .add(new CapturedSend(packet, secret, encryption, recipient));
    }

    private Set<InetSocketAddress> sentAddresses() {
        Set<InetSocketAddress> addresses = new HashSet<>();
        for (CapturedSend send : sent) {
            addresses.add(send.recipient);
        }
        return addresses;
    }

    private VoiceServerSession addSession(String name, boolean withUdpAddress) {
        UUID playerUuid = UUID.randomUUID();
        UUID secret = UUID.randomUUID();
        VoiceServerSession session = new VoiceServerSession(
            playerUuid,
            name,
            secret,
            new AesEncryption(VoiceProtocol.deriveKey(secret)));
        if (withUdpAddress) session.touch(new InetSocketAddress("127.0.0.1", nextPort++));
        sessions.put(playerUuid, session);
        return session;
    }

    private void addSnapshot(VoiceServerSession session, double x, double y, double z, int dimension) {
        snapshot.put(
            session.getPlayerUuid(),
            new PlayerSnapshot(session.getPlayerUuid(), session.getPlayerName(), x, y, z, dimension));
    }

    private static PlayerAudioPacket audio(long sequenceNumber, byte[] data) {
        return new PlayerAudioPacket(sequenceNumber, data, UUID.randomUUID(), (short) 48, false);
    }

    private static final class CapturedSend {

        final Packet<?> packet;
        final UUID secret;
        final Encryption encryption;
        final InetSocketAddress recipient;

        CapturedSend(Packet<?> packet, UUID secret, Encryption encryption, InetSocketAddress recipient) {
            this.packet = packet;
            this.secret = secret;
            this.encryption = encryption;
            this.recipient = recipient;
        }
    }
}
