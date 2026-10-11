package com.enn3developer.gtnhvoice.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.server.IListenerRegistration;
import com.enn3developer.gtnhvoice.api.server.IVoiceSession;
import com.enn3developer.gtnhvoice.api.server.IVoiceSessionListener;
import com.enn3developer.gtnhvoice.api.server.SessionEndReason;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;

/**
 * {@link SessionLifecycle} on its own, fed transitions directly - including the orders racing threads can produce,
 * which the server manager cannot be made to produce on demand. The server thread is a queue the test drains;
 * wiped players are recorded into the same log as the events, so their relative order is visible.
 */
class SessionLifecycleTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000b0b");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-0000000ca201");

    private final Queue<Runnable> serverThread = new ConcurrentLinkedQueue<>();
    private final List<String> log = new ArrayList<>();
    private final Map<IVoiceSession, String> labels = new IdentityHashMap<>();
    private final SessionLifecycle lifecycle = new SessionLifecycle(
        serverThread::add,
        player -> log.add("wipe " + name(player)));
    private long nextSessionId;

    private static String name(UUID player) {
        if (ALICE.equals(player)) return "alice";
        return BOB.equals(player) ? "bob" : "carol";
    }

    private void tick() {
        Runnable task;
        while ((task = serverThread.poll()) != null) {
            task.run();
        }
    }

    private VoiceServerSession session(String label, UUID player) {
        VoiceServerSession session = new VoiceServerSession(
            player,
            name(player),
            new UUID(0L, ++nextSessionId),
            new AesEncryption(new byte[32]));
        labels.put(session, label);
        return session;
    }

    private IVoiceSessionListener recorder(List<String> into) {
        return new IVoiceSessionListener() {

            @Override
            public void sessionStarted(IVoiceSession session) {
                into.add("started " + labels.get(session));
            }

            @Override
            public void sessionEnded(IVoiceSession session, SessionEndReason reason) {
                into.add("ended " + labels.get(session) + " " + reason);
            }
        };
    }

    @Test
    void startReportedFromAnotherThreadIsDeliveredOnTheThreadThatDrains() throws InterruptedException {
        List<Thread> deliveredOn = new ArrayList<>();
        lifecycle.register(new IVoiceSessionListener() {

            @Override
            public void sessionStarted(IVoiceSession session) {
                deliveredOn.add(Thread.currentThread());
            }
        });
        VoiceServerSession alice = session("alice#1", ALICE);
        Thread netty = new Thread(() -> lifecycle.started(alice), "Netty Server IO");
        netty.start();
        netty.join();
        List<Thread> beforeTheTick = new ArrayList<>(deliveredOn);

        tick();

        assertEquals(List.of(), beforeTheTick);
        assertEquals(List.of(Thread.currentThread()), deliveredOn);
    }

    @Test
    void newerSessionStartingAheadOfTheOlderOnesReportedEndReplacesItWithoutAWipe() {
        // The race: the reaper removed alice#1 but the handshake thread reported alice#2 before the reaper
        // reported alice#1's end. alice#2's start must not be followed by anything from alice#1.
        lifecycle.register(recorder(log));
        VoiceServerSession first = session("alice#1", ALICE);
        lifecycle.started(first);
        tick();

        lifecycle.started(session("alice#2", ALICE));
        lifecycle.ended(first, SessionEndReason.TIMED_OUT);
        tick();

        assertEquals(List.of("started alice#1", "ended alice#1 REPLACED", "started alice#2"), log);
    }

    @Test
    void lateListenerGetsTheLiveSessionsInStartOrderBeforeAnyLaterEvent() {
        List<String> early = new ArrayList<>();
        lifecycle.register(recorder(early));
        lifecycle.started(session("alice#1", ALICE));
        lifecycle.started(session("bob#1", BOB));
        tick();

        List<String> late = new ArrayList<>();
        lifecycle.register(recorder(late));
        lifecycle.started(session("carol#1", CAROL));
        tick();

        assertEquals(List.of("started alice#1", "started bob#1", "started carol#1"), late);
        assertEquals(List.of("started alice#1", "started bob#1", "started carol#1"), early, "no replay to the early one");
    }

    @Test
    void lateListenerGetsItsReplayedStartBeforeAnEndReportedRightAfterItRegistered() {
        VoiceServerSession alice = session("alice#1", ALICE);
        lifecycle.started(alice);
        tick();

        List<String> late = new ArrayList<>();
        lifecycle.register(recorder(late));
        lifecycle.ended(alice, SessionEndReason.LOGOUT);
        tick();

        assertEquals(List.of("started alice#1", "ended alice#1 LOGOUT"), late);
    }

    @Test
    void throwingListenerDoesNotKeepTheEventFromTheOthers() {
        lifecycle.register(new IVoiceSessionListener() {

            @Override
            public void sessionStarted(IVoiceSession session) {
                throw new IllegalStateException("addon bug");
            }
        });
        lifecycle.register(recorder(log));

        lifecycle.started(session("alice#1", ALICE));
        tick();

        assertEquals(List.of("started alice#1"), log);
    }

    @Test
    void unregisteredListenerReceivesNothingMore() {
        List<String> events = new ArrayList<>();
        IListenerRegistration registration = lifecycle.register(recorder(events));
        VoiceServerSession alice = session("alice#1", ALICE);
        lifecycle.started(alice);
        tick();

        registration.unregister();
        lifecycle.ended(alice, SessionEndReason.LOGOUT);
        tick();

        assertEquals(List.of("started alice#1"), events);
    }

    @Test
    void queriesAgreeWithTheEventBeingDelivered() {
        List<Optional<IVoiceSession>> seen = new ArrayList<>();
        lifecycle.register(new IVoiceSessionListener() {

            @Override
            public void sessionStarted(IVoiceSession session) {
                seen.add(lifecycle.sessionOf(ALICE));
            }

            @Override
            public void sessionEnded(IVoiceSession session, SessionEndReason reason) {
                seen.add(lifecycle.sessionOf(ALICE));
            }
        });
        VoiceServerSession alice = session("alice#1", ALICE);
        lifecycle.started(alice);
        tick();
        lifecycle.ended(alice, SessionEndReason.LOGOUT);
        tick();

        assertEquals(List.of(Optional.of(alice), Optional.empty()), seen);
    }

    @Test
    void sessionsListsTheLiveSessionsInStartOrder() {
        VoiceServerSession bob = session("bob#1", BOB);
        VoiceServerSession alice = session("alice#1", ALICE);
        lifecycle.started(bob);
        lifecycle.started(alice);
        tick();

        assertEquals(List.of(bob, alice), new ArrayList<>(lifecycle.sessions()));
    }

    @Test
    void serverStopEndsEveryLiveSessionAndDropsWhatWasReportedAfterTheFinalDrain() {
        lifecycle.register(recorder(log));
        lifecycle.started(session("alice#1", ALICE));
        tick();
        lifecycle.started(session("bob#1", BOB)); // reported after the stop's final drain: bob#1 never existed

        lifecycle.serverStopped();
        tick();

        assertEquals(List.of("started alice#1", "ended alice#1 SERVER_STOPPED"), log);
    }
}
