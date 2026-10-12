package com.enn3developer.gtnhvoice.api.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.api.server.group.IGroup;
import com.enn3developer.gtnhvoice.api.server.group.RoutingContext;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;
import com.enn3developer.gtnhvoice.server.group.LocalGroup;

/**
 * {@link GtnhVoiceApi#proximityRange()}: the range the built-in local group cuts off at, read from the live config on
 * every call, and readable from a group's {@code route()} on the network thread. Everyone stands on the x axis of
 * dimension 0, so a player's x is their distance from the speaker at the origin.
 */
class ProximityRangeTest {

    private final int savedDistance = Config.distance;
    private final int savedMaxDistance = Config.maxDistance;
    private final Map<UUID, VoiceServerSession> sessions = new HashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshot = new HashMap<>();
    private final Map<UUID, String> namesBySessionId = new HashMap<>();
    private final List<String> heard = new ArrayList<>();
    private int nextPort = 41_000;

    @AfterEach
    void restoreTheConfig() {
        Config.distance = savedDistance;
        Config.maxDistance = savedMaxDistance;
    }

    @Test
    void isTheSmallerOfDistanceAndMaxDistance() {
        Config.distance = 48;
        Config.maxDistance = 128;
        assertEquals(48, GtnhVoiceApi.proximityRange());

        Config.distance = 200;
        Config.maxDistance = 64;
        assertEquals(64, GtnhVoiceApi.proximityRange());
    }

    @Test
    void localGroupCutsOffExactlyAtTheRangeTheApiReports() {
        Config.distance = 20;
        Config.maxDistance = 128;
        VoiceServerSession speaker = player("speaker", 0);
        player("atTheRange", 20);
        player("justPastIt", 20.01);

        new LocalGroup().route(context(speaker));

        assertEquals(20, GtnhVoiceApi.proximityRange());
        assertEquals(List.of("atTheRange"), heard);
    }

    @Test
    void aConfigChangeReachesTheApiAndTheLocalGroupAlike() {
        Config.distance = 20;
        Config.maxDistance = 128;
        VoiceServerSession speaker = player("speaker", 0);
        player("near", 8);
        player("mid", 15);

        Config.maxDistance = 10;
        new LocalGroup().route(context(speaker));

        assertEquals(10, GtnhVoiceApi.proximityRange());
        assertEquals(List.of("near"), heard, "mid, at 15, is past the new 10-block cap");
    }

    @Test
    void aGroupReadsItInsideRouteOnTheNetworkThread() throws InterruptedException {
        // What BeCom's call group does: leave out listeners who hear the speaker directly, within min(16, range).
        Config.distance = 12;
        Config.maxDistance = 128;
        VoiceServerSession speaker = player("speaker", 0);
        player("hearsThemDirectly", 10);
        player("tooFarToHearThem", 14);
        IGroup call = new IGroup() {

            @Override
            public String getName() {
                return "call";
            }

            @Override
            public String getDisplayName() {
                return "Call";
            }

            @Override
            public void route(RoutingContext context) {
                double direct = Math.min(16, GtnhVoiceApi.proximityRange());
                context.getAllSessions()
                    .excludeSelf()
                    .filter(
                        context.withinDistanceOf(0, 64, 0, 0, direct)
                            .negate())
                    .send(SourceState.FLAT);
            }
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread network = new Thread(() -> {
            try {
                call.route(context(speaker));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "gtnhvoice-test-network");
        network.start();
        network.join();

        assertNull(failure.get());
        assertEquals(List.of("tooFarToHearThem"), heard);
    }

    private RoutingContext context(VoiceServerSession speaker) {
        return RoutingContext.builder()
            .packetSender((packet, sessionId, encryption, recipient) -> heard.add(namesBySessionId.get(sessionId)))
            .positionSnapshot(snapshot)
            .sessions(sessions)
            .speakerSession(speaker)
            .audio(new PlayerAudioPacket(1L, new byte[] { 1 }, ActivationIds.DEFAULT, (short) 0, false))
            .group(new LocalGroup())
            .membershipTest((playerUuid, group) -> true)
            .groupIdResolver(group -> 0)
            .build();
    }

    /** A reachable player standing {@code x} blocks from the origin, along the x axis of dimension 0. */
    private VoiceServerSession player(String name, double x) {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        VoiceServerSession session = new VoiceServerSession(
            playerUuid,
            name,
            sessionId,
            new AesEncryption(new byte[32]));
        session.touch(new InetSocketAddress("127.0.0.1", nextPort++));
        sessions.put(playerUuid, session);
        snapshot.put(playerUuid, new PlayerSnapshot(playerUuid, name, x, 64, 0, 0));
        namesBySessionId.put(sessionId, name);
        return session;
    }
}
