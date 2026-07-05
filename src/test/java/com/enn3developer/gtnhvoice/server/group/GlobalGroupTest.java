package com.enn3developer.gtnhvoice.server.group;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
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
 * Exercises {@link GlobalGroup}'s everyone-but-the-speaker broadcast and flat-flagged outgoing packets against a
 * capturing {@link PacketSender}, mirroring {@link LocalGroupTest} - no Netty transport involved.
 */
class GlobalGroupTest {

    private final GlobalGroup group = new GlobalGroup();
    private final List<CapturedSend> sent = new ArrayList<>();
    private final Map<UUID, VoiceServerSession> sessions = new HashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshot = new HashMap<>();
    private int nextPort = 41_000;

    @Test
    void broadcastsFlatToEveryoneExceptSpeakerRegardlessOfDimensionAndDistance() {
        VoiceServerSession speaker = addSession("speaker", true);
        addSnapshot(speaker, 3, 64, -7, 0);
        VoiceServerSession nearby = addSession("nearby", true);
        addSnapshot(nearby, 10, 64, 0, 0);
        VoiceServerSession farBeyondCutoff = addSession("farBeyondCutoff", true);
        addSnapshot(farBeyondCutoff, 10_000, 64, 0, 0);
        VoiceServerSession otherDim = addSession("otherDim", true);
        addSnapshot(otherDim, 5, 64, 0, -1);
        VoiceServerSession noSnapshot = addSession("noSnapshot", true);
        VoiceServerSession noUdp = addSession("noUdp", false);
        addSnapshot(noUdp, 5, 64, 0, 0);

        byte[] opusData = { 4, 5, 6 };
        group.route(context(speaker, audio(11L, opusData)));

        assertEquals(4, sent.size());
        Set<InetSocketAddress> recipients = new HashSet<>();
        for (CapturedSend send : sent) {
            recipients.add(send.recipient);

            SourceAudioPacket forwarded = assertInstanceOf(SourceAudioPacket.class, send.packet);
            assertFalse(forwarded.isPositional());
            assertEquals(speaker.getPlayerUuid(), forwarded.getSourceId());
            assertEquals(11L, forwarded.getSequenceNumber());
            assertArrayEquals(opusData, forwarded.getData());
            assertEquals(3, forwarded.getX());
            assertEquals(64, forwarded.getY());
            assertEquals(-7, forwarded.getZ());
        }

        assertTrue(recipients.contains(nearby.getLastAddress()));
        assertTrue(recipients.contains(farBeyondCutoff.getLastAddress()));
        assertTrue(recipients.contains(otherDim.getLastAddress()));
        assertTrue(recipients.contains(noSnapshot.getLastAddress()));
        assertFalse(recipients.contains(speaker.getLastAddress()));
    }

    @Test
    void skipsRecipientWithoutUdpAddress() {
        VoiceServerSession speaker = addSession("speaker", true);
        addSnapshot(speaker, 0, 64, 0, 0);
        addSession("noUdp", false);

        group.route(context(speaker, audio(1L, new byte[] { 9 })));

        assertTrue(sent.isEmpty());
    }

    @Test
    void speakerWithoutSnapshotStillRoutesWithZeroCoordinates() {
        VoiceServerSession speaker = addSession("speaker", true);
        VoiceServerSession listener = addSession("listener", true);
        addSnapshot(listener, 1, 64, 0, 0);

        byte[] opusData = { 7 };
        group.route(context(speaker, audio(3L, opusData)));

        assertEquals(1, sent.size());
        CapturedSend only = sent.get(0);
        assertEquals(listener.getLastAddress(), only.recipient);

        SourceAudioPacket forwarded = assertInstanceOf(SourceAudioPacket.class, only.packet);
        assertFalse(forwarded.isPositional());
        assertEquals(speaker.getPlayerUuid(), forwarded.getSourceId());
        assertEquals(3L, forwarded.getSequenceNumber());
        assertArrayEquals(opusData, forwarded.getData());
        assertEquals(0, forwarded.getX());
        assertEquals(0, forwarded.getY());
        assertEquals(0, forwarded.getZ());
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
