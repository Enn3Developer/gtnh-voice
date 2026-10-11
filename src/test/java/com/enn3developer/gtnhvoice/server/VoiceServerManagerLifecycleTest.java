package com.enn3developer.gtnhvoice.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.server.IVoiceSession;
import com.enn3developer.gtnhvoice.api.server.IVoiceSessionListener;
import com.enn3developer.gtnhvoice.api.server.SessionEndReason;
import com.enn3developer.gtnhvoice.api.server.group.IGroup;
import com.enn3developer.gtnhvoice.api.server.group.RoutingContext;
import com.enn3developer.gtnhvoice.core.encryption.aes.AesEncryption;

import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Session lifecycle events through the real server manager: sessions are installed, logged out and reaped through
 * the same entry points the handshake, logout event and reaper use, and events reach listeners when the server tick
 * drains the manager's queue - the test thread plays the server thread. Only the game server is stubbed (absent),
 * which the manager's queued network sends tolerate. A recording group logs the membership wipe ({@code wipe}) and
 * the registry clear, so their order relative to the events is visible in one log.
 */
class VoiceServerManagerLifecycleTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000b0b");
    // One millisecond past the manager's 3-minute session timeout.
    private static final long PAST_THE_SESSION_TIMEOUT = 3 * 60_000L + 1;

    private final VoiceServerManager manager = new VoiceServerManager(() -> null);
    private final List<String> log = new ArrayList<>();
    private final Set<Thread> callbackThreads = new HashSet<>();
    private final Map<IVoiceSession, String> labels = new IdentityHashMap<>();
    private final IGroup squad = new RecordingGroup();
    private long nextSessionId;

    /** Logs the wipe and clear hooks the group manager drives - the observable side of the membership wipe. */
    private final class RecordingGroup implements IGroup {

        @Override
        public String getName() {
            return "squad";
        }

        @Override
        public String getDisplayName() {
            return "Squad";
        }

        @Override
        public void route(RoutingContext context) {}

        @Override
        public void onPlayerRemoved(UUID playerUuid) {
            callbackThreads.add(Thread.currentThread());
            log.add("wipe " + (ALICE.equals(playerUuid) ? "alice" : "bob"));
        }

        @Override
        public void clear() {
            log.add("registry cleared");
        }
    }

    /** Logs every event; when {@code joinOnStart}, also joins the started player to the squad, as an addon would. */
    private IVoiceSessionListener recorder(List<String> into, boolean joinOnStart) {
        return new IVoiceSessionListener() {

            @Override
            public void sessionStarted(IVoiceSession session) {
                callbackThreads.add(Thread.currentThread());
                into.add("started " + labels.get(session));
                if (joinOnStart) manager.getGroupManager()
                    .join(session.getPlayerUuid(), squad);
            }

            @Override
            public void sessionEnded(IVoiceSession session, SessionEndReason reason) {
                callbackThreads.add(Thread.currentThread());
                into.add("ended " + labels.get(session) + " " + reason);
            }
        };
    }

    @BeforeEach
    void registerTheSquad() {
        manager.getGroupManager()
            .registerGroup(squad);
        tick();
    }

    private void tick() {
        manager.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
    }

    private VoiceServerSession session(String label, UUID player, Object connection) {
        VoiceServerSession session = new VoiceServerSession(
            player,
            ALICE.equals(player) ? "alice" : "bob",
            new UUID(0L, ++nextSessionId),
            new AesEncryption(new byte[32]),
            new byte[0],
            new byte[0],
            connection);
        labels.put(session, label);
        return session;
    }

    private Set<UUID> squadMembers() {
        return manager.getGroupManager()
            .membersOf(squad);
    }

    @Test
    void logoutWipesMembershipsBeforeSessionEnded() {
        manager.getSessionLifecycle()
            .register(recorder(log, false));
        Object connection = new Object();
        manager.installSession(session("alice#1", ALICE, connection));
        tick();
        manager.getGroupManager()
            .join(ALICE, squad);

        manager.endSessionOnLogout(ALICE, connection);
        tick();

        assertEquals(List.of("started alice#1", "wipe alice", "ended alice#1 LOGOUT"), log);
        assertEquals(Set.of(), squadMembers());
    }

    @Test
    void reaperTimeoutWipesOnTheServerThreadBeforeSessionEnded() throws InterruptedException {
        manager.getSessionLifecycle()
            .register(recorder(log, false));
        VoiceServerSession stale = session("alice#1", ALICE, new Object());
        manager.installSession(stale);
        tick();

        Thread reaper = new Thread(
            () -> manager.reapStaleSessions(stale.getLastSeenMillis() + PAST_THE_SESSION_TIMEOUT),
            "gtnhvoice-session-reaper");
        reaper.start();
        reaper.join();
        List<String> afterReaperRan = new ArrayList<>(log);
        tick();

        assertEquals(List.of("started alice#1"), afterReaperRan, "neither the wipe nor the event runs on the reaper");
        assertEquals(List.of("started alice#1", "wipe alice", "ended alice#1 TIMED_OUT"), log);
        assertEquals(Set.of(Thread.currentThread()), callbackThreads, "both ran on the thread that ticked");
    }

    @Test
    void replacedSessionEndsBeforeItsSuccessorStartsAndKeepsMemberships() {
        manager.getSessionLifecycle()
            .register(recorder(log, true));
        manager.installSession(session("alice#1", ALICE, new Object()));
        tick();

        manager.installSession(session("alice#2", ALICE, new Object()));
        tick();

        assertEquals(List.of("started alice#1", "ended alice#1 REPLACED", "started alice#2"), log);
        assertEquals(Set.of(ALICE), squadMembers());
    }

    @Test
    void joinInSessionStartedSurvivesARelogWhoseLogoutLandsInTheSameTick() {
        manager.getSessionLifecycle()
            .register(recorder(log, true));
        Object firstConnection = new Object();
        manager.installSession(session("alice#1", ALICE, firstConnection));
        tick();

        manager.endSessionOnLogout(ALICE, firstConnection);
        manager.installSession(session("alice#2", ALICE, new Object()));
        tick();

        assertEquals(List.of("started alice#1", "wipe alice", "ended alice#1 LOGOUT", "started alice#2"), log);
        assertEquals(Set.of(ALICE), squadMembers(), "alice#2's join came after alice#1's wipe");
    }

    @Test
    void joinInSessionStartedSurvivesATimeoutRacingTheReconnect() {
        manager.getSessionLifecycle()
            .register(recorder(log, true));
        VoiceServerSession stale = session("alice#1", ALICE, new Object());
        manager.installSession(stale);
        tick();

        manager.reapStaleSessions(stale.getLastSeenMillis() + PAST_THE_SESSION_TIMEOUT);
        manager.installSession(session("alice#2", ALICE, new Object()));
        tick();

        assertEquals(List.of("started alice#1", "wipe alice", "ended alice#1 TIMED_OUT", "started alice#2"), log);
        assertEquals(Set.of(ALICE), squadMembers(), "alice#2's join came after alice#1's wipe");
    }

    @Test
    void logoutOfAConnectionThatWasAlreadyReplacedEndsNothing() {
        manager.getSessionLifecycle()
            .register(recorder(log, true));
        Object oldConnection = new Object();
        manager.installSession(session("alice#1", ALICE, oldConnection));
        manager.installSession(session("alice#2", ALICE, new Object()));

        manager.endSessionOnLogout(ALICE, oldConnection); // the old connection's logout arrives after the reconnect
        tick();

        assertEquals(List.of("started alice#1", "ended alice#1 REPLACED", "started alice#2"), log);
        assertEquals(Set.of(ALICE), squadMembers());
    }

    @Test
    void sessionStartsBeforeTheClientsFirstDatagram() {
        List<Boolean> udpAddressKnownAtStart = new ArrayList<>();
        manager.getSessionLifecycle()
            .register(new IVoiceSessionListener() {

                @Override
                public void sessionStarted(IVoiceSession session) {
                    udpAddressKnownAtStart.add(session.hasUdpAddress());
                }
            });

        manager.installSession(session("alice#1", ALICE, new Object()));
        tick();

        assertEquals(List.of(false), udpAddressKnownAtStart);
    }

    @Test
    void lateListenerGetsTheLiveSessionsReplayedOnTheNextTick() {
        manager.installSession(session("alice#1", ALICE, new Object()));
        manager.installSession(session("bob#1", BOB, new Object()));
        tick();

        List<String> late = new ArrayList<>();
        manager.getSessionLifecycle()
            .register(recorder(late, false));
        List<String> beforeTheTick = new ArrayList<>(late);
        tick();

        assertEquals(List.of(), beforeTheTick, "replayed on the server thread, not by register()");
        assertEquals(List.of("started alice#1", "started bob#1"), late);
    }

    @Test
    void voiceServerStopClearsTheRegistryThenEndsEverySession() {
        manager.getSessionLifecycle()
            .register(recorder(log, false));
        manager.installSession(session("alice#1", ALICE, new Object()));
        tick();

        manager.shutDownSessions();

        assertEquals(List.of("started alice#1", "registry cleared", "ended alice#1 SERVER_STOPPED"), log);
        assertEquals(Collections.emptyList(), new ArrayList<>(manager.getSessionLifecycle()
            .sessions()));
    }

    @Test
    void listenersSurviveAVoiceServerStop() {
        List<String> events = new ArrayList<>();
        manager.getSessionLifecycle()
            .register(recorder(events, false));
        manager.shutDownSessions();

        manager.installSession(session("alice#1", ALICE, new Object()));
        tick();

        assertEquals(List.of("started alice#1"), events);
    }

    @Test
    void nothingIsDeliveredUntilTheServerTicks() {
        manager.getSessionLifecycle()
            .register(recorder(log, false));

        manager.installSession(session("alice#1", ALICE, new Object()));

        assertEquals(List.of(), log);
        assertFalse(manager.getSessionLifecycle()
            .sessionOf(ALICE)
            .isPresent(), "queries follow the delivered events");
    }
}
