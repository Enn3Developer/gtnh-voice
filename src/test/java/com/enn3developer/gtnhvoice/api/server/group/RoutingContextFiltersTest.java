package com.enn3developer.gtnhvoice.api.server.group;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

import com.enn3developer.gtnhvoice.api.server.PacketSender;
import com.enn3developer.gtnhvoice.api.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.api.server.SourceState;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

/**
 * Exercises {@link RoutingContext}'s position-dependent predicate factories ({@link RoutingContext#inDimension}
 * and {@link RoutingContext#withinDistanceOf}) end to end through {@link RecipientSelection#filter} against a
 * capturing {@link PacketSender} - the same harness shape as {@link RecipientSelectionTest}. Both factories are
 * absolute (no speaker semantics), so no speaker-snapshot cases exist here.
 */
class RoutingContextFiltersTest {

    /** Marker group with no behavior - the factories never consult the routed group. */
    private static final class NamedGroup implements IGroup {

        @Override
        public String getName() {
            return "routed";
        }

        @Override
        public String getDisplayName() {
            return "routed";
        }

        @Override
        public void route(RoutingContext context) {}
    }

    private final IGroup routedGroup = new NamedGroup();
    private final List<InetSocketAddress> sent = new ArrayList<>();
    private final Map<UUID, VoiceServerSession> sessions = new HashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshot = new HashMap<>();
    private int nextPort = 43_000;

    @Test
    void inDimensionKeepsMatchesAndDropsWrongDimensionAndSnapshotless() {
        VoiceServerSession speaker = addSession("speaker");
        VoiceServerSession inDim = addSession("inDim");
        addSnapshot(inDim, 0, 64, 0, 7);
        VoiceServerSession wrongDim = addSession("wrongDim");
        addSnapshot(wrongDim, 0, 64, 0, 0);
        addSession("noSnapshot");

        RoutingContext context = context(speaker);
        context.getAllSessions()
            .filter(context.inDimension(7))
            .send(SourceState.POSITIONAL);

        assertEquals(Collections.singleton(inDim.getLastAddress()), sentAddresses());
    }

    @Test
    void withinDistanceOfKeepsInsideAndExactlyAtRadius() {
        VoiceServerSession speaker = addSession("speaker");
        VoiceServerSession inside = addSession("inside");
        addSnapshot(inside, 105, 64, 200, 0);
        VoiceServerSession atRadius = addSession("atRadius");
        addSnapshot(atRadius, 148, 64, 200, 0);

        RoutingContext context = context(speaker);
        context.getAllSessions()
            .filter(context.withinDistanceOf(100, 64, 200, 0, 48))
            .send(SourceState.POSITIONAL);

        Set<InetSocketAddress> expected = new HashSet<>();
        expected.add(inside.getLastAddress());
        expected.add(atRadius.getLastAddress());
        assertEquals(expected, sentAddresses());
    }

    @Test
    void withinDistanceOfDropsBeyondRadiusWrongDimensionAndSnapshotless() {
        VoiceServerSession speaker = addSession("speaker");
        VoiceServerSession inside = addSession("inside");
        addSnapshot(inside, 100, 64, 200, 0);
        VoiceServerSession beyond = addSession("beyond");
        addSnapshot(beyond, 148.001, 64, 200, 0);
        VoiceServerSession wrongDim = addSession("wrongDim");
        addSnapshot(wrongDim, 100, 64, 200, 1);
        addSession("noSnapshot");

        RoutingContext context = context(speaker);
        context.getAllSessions()
            .filter(context.withinDistanceOf(100, 64, 200, 0, 48))
            .send(SourceState.POSITIONAL);

        assertEquals(Collections.singleton(inside.getLastAddress()), sentAddresses());
    }

    private RoutingContext context(VoiceServerSession speaker) {
        return RoutingContext.builder()
            .packetSender((packet, secret, encryption, recipient) -> sent.add(recipient))
            .positionSnapshot(snapshot)
            .sessions(sessions)
            .speakerSession(speaker)
            .audio(new PlayerAudioPacket(7L, new byte[] { 1, 2, 3 }, UUID.randomUUID(), (short) 48, false))
            .group(routedGroup)
            .membershipTest((playerUuid, g) -> true)
            .groupIdResolver(g -> 0)
            .build();
    }

    private Set<InetSocketAddress> sentAddresses() {
        return new HashSet<>(sent);
    }

    private VoiceServerSession addSession(String name) {
        UUID playerUuid = UUID.randomUUID();
        UUID secret = UUID.randomUUID();
        VoiceServerSession session = new VoiceServerSession(
            playerUuid,
            name,
            secret,
            new AesEncryption(new byte[32]));
        session.touch(new InetSocketAddress("127.0.0.1", nextPort++));
        sessions.put(playerUuid, session);
        return session;
    }

    private void addSnapshot(VoiceServerSession session, double x, double y, double z, int dimension) {
        snapshot.put(
            session.getPlayerUuid(),
            new PlayerSnapshot(session.getPlayerUuid(), session.getPlayerName(), x, y, z, dimension));
    }
}
