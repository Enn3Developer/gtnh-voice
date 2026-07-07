package com.enn3developer.gtnhvoice.api.server.group;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.server.IVoiceSession;
import com.enn3developer.gtnhvoice.api.server.PacketSender;
import com.enn3developer.gtnhvoice.api.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.api.server.SourceState;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

/**
 * Exercises {@link RoutingContext}'s own contracts - {@link RoutingContext#sendTo} resolving recipients by UUID
 * (never trusting a foreign {@link IVoiceSession} implementation), building the outgoing packet from the
 * context's frame, and the {@link RoutingContext.Builder}'s missing-field validation - against a capturing
 * {@link PacketSender}.
 */
class RoutingContextTest {

    /** Marker group with no behavior - these tests never route through a group. */
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

    /** A foreign IVoiceSession implementation - only identity, no concrete session behind it. */
    private static final class FakeVoiceSession implements IVoiceSession {

        private final UUID playerUuid = UUID.randomUUID();

        @Override
        public UUID getPlayerUuid() {
            return playerUuid;
        }

        @Override
        public String getPlayerName() {
            return "fake";
        }

        @Override
        public boolean hasUdpAddress() {
            return true;
        }
    }

    private final IGroup routedGroup = new NamedGroup();
    private final List<CapturedSend> sent = new ArrayList<>();
    private final Map<UUID, VoiceServerSession> sessions = new HashMap<>();
    private int nextPort = 44_000;

    @Test
    void sendToUnknownRecipientNoOps() {
        VoiceServerSession speaker = addSession("speaker", true);
        RoutingContext context = context(speaker);

        context.sendTo(new FakeVoiceSession(), SourceState.POSITIONAL, 0, 0, 0);

        assertTrue(sent.isEmpty());
    }

    @Test
    void sendToKnownRecipientResolvesTheConcreteSession() {
        VoiceServerSession speaker = addSession("speaker", true);
        VoiceServerSession listener = addSession("listener", true);
        RoutingContext context = context(speaker);

        context.sendTo(listener, SourceState.POSITIONAL, 0, 0, 0);

        assertEquals(Collections.singletonList(listener.getLastAddress()), sentAddresses());
    }

    @Test
    void sendToRecipientWithoutUdpAddressNoOps() {
        VoiceServerSession speaker = addSession("speaker", true);
        VoiceServerSession noUdp = addSession("noUdp", false);
        RoutingContext context = context(speaker);

        context.sendTo(noUdp, SourceState.POSITIONAL, 0, 0, 0);

        assertTrue(sent.isEmpty());
    }

    @Test
    void sendToBuildsThePacketFromTheContextFrameAndGivenArguments() {
        VoiceServerSession speaker = addSession("speaker", true);
        VoiceServerSession listener = addSession("listener", true);
        RoutingContext context = context(speaker);

        context.sendTo(listener, SourceState.FLAT, 12.5, -3, 900);

        assertEquals(1, sent.size());
        SourceAudioPacket forwarded = assertInstanceOf(SourceAudioPacket.class, sent.get(0).packet);
        assertEquals(7L, forwarded.getSequenceNumber());
        assertArrayEquals(new byte[] { 1, 2, 3 }, forwarded.getData());
        assertEquals(speaker.getPlayerUuid(), forwarded.getSourceId());
        assertEquals(SourceState.FLAT, forwarded.getSourceState());
        assertEquals(12.5, forwarded.getX());
        assertEquals(-3, forwarded.getY());
        assertEquals(900, forwarded.getZ());
    }

    @Test
    void buildWithMissingFieldThrowsNamingIt() {
        VoiceServerSession speaker = addSession("speaker", true);

        RoutingContext.Builder missingSender = RoutingContext.builder()
            .positionSnapshot(new HashMap<>())
            .sessions(sessions)
            .speakerSession(speaker)
            .audio(audio())
            .group(routedGroup)
            .membershipResolver(playerUuid -> routedGroup);
        IllegalStateException noSender = assertThrows(IllegalStateException.class, missingSender::build);
        assertTrue(
            noSender.getMessage()
                .contains("packetSender"),
            noSender.getMessage());

        RoutingContext.Builder missingAudio = RoutingContext.builder()
            .packetSender((packet, secret, encryption, recipient) -> sent.add(new CapturedSend(packet, recipient)))
            .positionSnapshot(new HashMap<>())
            .sessions(sessions)
            .speakerSession(speaker)
            .group(routedGroup)
            .membershipResolver(playerUuid -> routedGroup);
        IllegalStateException noAudio = assertThrows(IllegalStateException.class, missingAudio::build);
        assertTrue(
            noAudio.getMessage()
                .contains("audio"),
            noAudio.getMessage());
    }

    private RoutingContext context(VoiceServerSession speaker) {
        return RoutingContext.builder()
            .packetSender((packet, secret, encryption, recipient) -> sent.add(new CapturedSend(packet, recipient)))
            .positionSnapshot(new HashMap<UUID, PlayerSnapshot>())
            .sessions(sessions)
            .speakerSession(speaker)
            .audio(audio())
            .group(routedGroup)
            .membershipResolver(playerUuid -> routedGroup)
            .build();
    }

    private List<InetSocketAddress> sentAddresses() {
        List<InetSocketAddress> addresses = new ArrayList<>();
        for (CapturedSend send : sent) {
            addresses.add(send.recipient);
        }
        return addresses;
    }

    private static PlayerAudioPacket audio() {
        return new PlayerAudioPacket(7L, new byte[] { 1, 2, 3 }, UUID.randomUUID(), (short) 48, false);
    }

    private VoiceServerSession addSession(String name, boolean withUdpAddress) {
        UUID playerUuid = UUID.randomUUID();
        UUID secret = UUID.randomUUID();
        VoiceServerSession session = new VoiceServerSession(
            playerUuid,
            name,
            secret,
            new AesEncryption(new byte[32]));
        if (withUdpAddress) session.touch(new InetSocketAddress("127.0.0.1", nextPort++));
        sessions.put(playerUuid, session);
        return session;
    }

    private static final class CapturedSend {

        final Packet<?> packet;
        final InetSocketAddress recipient;

        CapturedSend(Packet<?> packet, InetSocketAddress recipient) {
            this.packet = packet;
            this.recipient = recipient;
        }
    }
}
