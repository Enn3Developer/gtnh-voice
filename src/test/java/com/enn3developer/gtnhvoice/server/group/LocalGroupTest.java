package com.enn3developer.gtnhvoice.server.group;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.core.api.encryption.Encryption;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.network.VoiceProtocol;
import com.enn3developer.gtnhvoice.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

/**
 * Exercises {@link LocalGroup}'s recipient filters and outgoing-packet construction against a capturing
 * {@link PacketSender} - no Netty transport involved. Distances rely on {@link Config}'s compiled-in defaults
 * (distance=48, maxDistance=128, so the cutoff is 48m).
 */
class LocalGroupTest {

    private final LocalGroup group = new LocalGroup();
    private final List<CapturedSend> sent = new ArrayList<>();
    private final Map<UUID, VoiceServerSession> sessions = new HashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshot = new HashMap<>();
    private int nextPort = 40_000;

    @Test
    void routesOnlyToEligibleRecipientsWithPositionalPackets() {
        VoiceServerSession speaker = addSession("speaker", true);
        addSnapshot(speaker, 0, 64, 0, 0);
        VoiceServerSession inRange = addSession("inRange", true);
        addSnapshot(inRange, 10, 64, 0, 0);
        VoiceServerSession outOfRange = addSession("outOfRange", true);
        addSnapshot(outOfRange, 100, 64, 0, 0);
        VoiceServerSession otherDim = addSession("otherDim", true);
        addSnapshot(otherDim, 5, 64, 0, 1);
        VoiceServerSession noUdp = addSession("noUdp", false);
        addSnapshot(noUdp, 5, 64, 0, 0);
        addSession("noSnapshot", true);

        byte[] opusData = { 1, 2, 3 };
        group.route(context(speaker, audio(7L, opusData)));

        assertEquals(1, sent.size());
        CapturedSend only = sent.get(0);
        assertEquals(inRange.getSecret(), only.secret);
        assertSame(inRange.getEncryption(), only.encryption);
        assertEquals(inRange.getLastAddress(), only.recipient);

        SourceAudioPacket forwarded = assertInstanceOf(SourceAudioPacket.class, only.packet);
        assertTrue(forwarded.isPositional());
        assertEquals(speaker.getPlayerUuid(), forwarded.getSourceId());
        assertEquals(7L, forwarded.getSequenceNumber());
        assertArrayEquals(opusData, forwarded.getData());
        assertEquals(0, forwarded.getX());
        assertEquals(64, forwarded.getY());
        assertEquals(0, forwarded.getZ());
    }

    @Test
    void dropsFrameWhenSpeakerHasNoSnapshotYet() {
        VoiceServerSession speaker = addSession("speaker", true);
        VoiceServerSession listener = addSession("listener", true);
        addSnapshot(listener, 1, 64, 0, 0);

        group.route(context(speaker, audio(1L, new byte[] { 9 })));

        assertTrue(sent.isEmpty());
    }

    private RoutingContext context(VoiceServerSession speaker, PlayerAudioPacket audio) {
        PacketSender capturingSender = (packet, secret, encryption, recipient) -> sent
            .add(new CapturedSend(packet, secret, encryption, recipient));
        return RoutingContext.builder()
            .packetSender(capturingSender)
            .positionSnapshot(snapshot)
            .sessions(sessions)
            .speakerSession(speaker)
            .audio(audio)
            .group(group)
            .membershipResolver(playerUuid -> group)
            .build();
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
