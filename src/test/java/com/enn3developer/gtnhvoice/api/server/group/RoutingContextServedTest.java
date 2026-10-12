package com.enn3developer.gtnhvoice.api.server.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.server.ActivationIds;
import com.enn3developer.gtnhvoice.api.server.IVoiceSession;
import com.enn3developer.gtnhvoice.api.server.SourceState;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

/**
 * {@link RoutingContext#isServed} and the three silent drops {@link RoutingContext#sendTo} documents: each drop is
 * predicted by the check its javadoc names, so a group can tell before sending whether the send will go through.
 * Groups route through a {@link RoutingContext.Driver}, as on the server, in the order listed.
 */
class RoutingContextServedTest {

    private final Map<UUID, VoiceServerSession> sessions = new HashMap<>();
    private final Map<UUID, String> namesBySessionId = new HashMap<>();
    private final List<String> sent = new ArrayList<>();
    private int nextPort = 45_000;

    @Test
    void nobodyIsServedBeforeTheFirstSend() {
        VoiceServerSession speaker = session("speaker", true);
        VoiceServerSession listener = session("listener", true);

        RoutingContext context = driver(speaker).context();

        assertFalse(context.isServed(listener.getPlayerUuid()));
    }

    @Test
    void aSendMarksTheRecipientServedForTheRestOfTheFrame() {
        VoiceServerSession speaker = session("speaker", true);
        VoiceServerSession listener = session("listener", true);
        List<Boolean> seenByTheSender = new ArrayList<>();
        List<Boolean> seenByTheNextGroup = new ArrayList<>();
        IGroup sender = group("sender", context -> {
            context.sendTo(listener, SourceState.FLAT, 0, 0, 0);
            seenByTheSender.add(context.isServed(listener.getPlayerUuid()));
        });
        IGroup next = group("next", context -> seenByTheNextGroup.add(context.isServed(listener.getPlayerUuid())));

        driver(speaker).route(List.of(sender, next));

        assertEquals(List.of(true), seenByTheSender);
        assertEquals(List.of(true), seenByTheNextGroup);
    }

    @Test
    void aLowerPriorityGroupCountsOnlyTheContactsItsSendsReach() {
        VoiceServerSession speaker = session("speaker", true);
        VoiceServerSession inACall = session("inACall", true);
        VoiceServerSession onlyOnTheRadio = session("onlyOnTheRadio", true);
        List<String> radioContacts = new ArrayList<>();
        IGroup call = group("call", context -> context.sendTo(inACall, SourceState.FLAT, 0, 0, 0));
        IGroup radio = group("radio", context -> {
            for (IVoiceSession listener : List.of(inACall, onlyOnTheRadio)) {
                if (context.isServed(listener.getPlayerUuid())) continue;
                context.sendTo(listener, SourceState.FLAT, 0, 0, 0);
                radioContacts.add(listener.getPlayerName());
            }
        });

        driver(speaker).route(List.of(call, radio));

        assertEquals(List.of("onlyOnTheRadio"), radioContacts, "the call already reached inACall this frame");
        assertEquals(List.of("inACall", "onlyOnTheRadio"), sent);
    }

    @Test
    void eachDocumentedCheckPredictsWhetherTheSendGoesThrough() {
        VoiceServerSession speaker = session("speaker", true);
        VoiceServerSession reachable = session("reachable", true);
        VoiceServerSession noAddressYet = session("noAddressYet", false);
        IVoiceSession noLiveSession = foreignSession("noLiveSession");
        VoiceServerSession servedAlready = session("servedAlready", true);
        Map<String, Boolean> predicted = new LinkedHashMap<>();
        IGroup earlier = group("earlier", context -> context.sendTo(servedAlready, SourceState.FLAT, 0, 0, 0));
        IGroup checking = group("checking", context -> {
            for (IVoiceSession recipient : List.of(reachable, noAddressYet, noLiveSession, servedAlready)) {
                predicted.put(recipient.getPlayerName(), goesThrough(context, recipient));
                context.sendTo(recipient, SourceState.POSITIONAL, 0, 0, 0);
            }
        });

        driver(speaker).route(List.of(earlier, checking));

        assertEquals(
            Map.of("reachable", true, "noAddressYet", false, "noLiveSession", false, "servedAlready", false),
            predicted);
        assertEquals(List.of("servedAlready", "reachable"), sent, "exactly the predicted sends went out");
    }

    @Test
    void aDroppedSendLeavesThePlayerUnserved() {
        VoiceServerSession speaker = session("speaker", true);
        VoiceServerSession noAddressYet = session("noAddressYet", false);
        IVoiceSession noLiveSession = foreignSession("noLiveSession");
        RoutingContext context = driver(speaker).context();

        context.sendTo(noAddressYet, SourceState.FLAT, 0, 0, 0);
        context.sendTo(noLiveSession, SourceState.FLAT, 0, 0, 0);

        assertTrue(sent.isEmpty());
        assertFalse(context.isServed(noAddressYet.getPlayerUuid()));
        assertFalse(context.isServed(noLiveSession.getPlayerUuid()));
    }

    @Test
    void theNextFrameStartsWithNobodyServed() {
        VoiceServerSession speaker = session("speaker", true);
        VoiceServerSession listener = session("listener", true);
        driver(speaker).context()
            .sendTo(listener, SourceState.FLAT, 0, 0, 0);

        RoutingContext nextFrame = driver(speaker).context();

        assertFalse(nextFrame.isServed(listener.getPlayerUuid()));
    }

    /** sendTo's three documented checks, in its order: a live session, a UDP address, not yet served. */
    private static boolean goesThrough(RoutingContext context, IVoiceSession recipient) {
        IVoiceSession live = context.getSessionsByPlayerUuid()
            .get(recipient.getPlayerUuid());
        return live != null && live.hasUdpAddress() && !context.isServed(recipient.getPlayerUuid());
    }

    private RoutingContext.Driver driver(VoiceServerSession speaker) {
        return RoutingContext.builder()
            .packetSender((packet, sessionId, encryption, recipient) -> sent.add(namesBySessionId.get(sessionId)))
            .positionSnapshot(new HashMap<>())
            .sessions(sessions)
            .speakerSession(speaker)
            .audio(new PlayerAudioPacket(1L, new byte[] { 1 }, ActivationIds.DEFAULT, (short) 0, false))
            .group(group("first", context -> {}))
            .membershipTest((playerUuid, group) -> true)
            .groupIdResolver(group -> 0)
            .buildDriver();
    }

    private static IGroup group(String name, Consumer<RoutingContext> behavior) {
        return new IGroup() {

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDisplayName() {
                return name;
            }

            @Override
            public void route(RoutingContext context) {
                behavior.accept(context);
            }
        };
    }

    private VoiceServerSession session(String name, boolean withUdpAddress) {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        VoiceServerSession session = new VoiceServerSession(
            playerUuid,
            name,
            sessionId,
            new AesEncryption(new byte[32]));
        if (withUdpAddress) session.touch(new InetSocketAddress("127.0.0.1", nextPort++));
        sessions.put(playerUuid, session);
        namesBySessionId.put(sessionId, name);
        return session;
    }

    /** A session view with no live session behind it: an addon's own implementation, or a session that ended. */
    private static IVoiceSession foreignSession(String name) {
        UUID playerUuid = UUID.randomUUID();
        return new IVoiceSession() {

            @Override
            public UUID getPlayerUuid() {
                return playerUuid;
            }

            @Override
            public String getPlayerName() {
                return name;
            }

            @Override
            public boolean hasUdpAddress() {
                return true;
            }
        };
    }
}
