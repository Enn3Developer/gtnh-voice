package com.enn3developer.gtnhvoice.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.api.server.IListenerRegistration;
import com.enn3developer.gtnhvoice.api.server.IVoiceSession;
import com.enn3developer.gtnhvoice.api.server.IVoiceSessionListener;
import com.enn3developer.gtnhvoice.api.server.SessionEndReason;
import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;

/**
 * Owns the addon-facing session lifecycle (see {@link IVoiceSessionListener} for the contract): the listener
 * registrations, the sessions listeners have been told about, and the ordered delivery of their events on the
 * server thread.
 * <p>
 * The server manager reports transitions from whichever thread they happen on - {@link #started} from the Netty
 * thread, {@link #ended} from the server or reaper thread - into a FIFO, and hands {@link #drain} to the server
 * thread through its tick queue. Draining replays each transition against the sessions listeners currently know,
 * which is what makes the ordering promises hold whatever order racing threads enqueued in: a session whose newer
 * successor starts first is ended as {@link SessionEndReason#REPLACED} right before that successor, keeping its
 * memberships, and its own late end is dropped as owed to nobody. The membership wipe of a logout or timeout runs
 * here too, on the server thread, immediately before its {@code sessionEnded}.
 * <p>
 * The transport maps stay the server manager's; this class never reads them.
 */
public final class SessionLifecycle {

    private static final long FAILURE_LOG_INTERVAL_MILLIS = 5000L;

    private final Consumer<Runnable> serverThread;
    private final Consumer<UUID> membershipWipe;

    private final Queue<Transition> transitions = new ConcurrentLinkedQueue<>();
    private final List<Registration> registrations = new CopyOnWriteArrayList<>();
    // Server thread only: each player's session as listeners know it - announced and not yet ended - in start order.
    private final Map<UUID, VoiceServerSession> live = new LinkedHashMap<>();
    // Immutable copy of live, republished on every change, for the queries.
    private volatile Map<UUID, IVoiceSession> liveView = Collections.emptyMap();
    private final AtomicLong lastFailureLogMillis = new AtomicLong();

    /**
     * @param serverThread   schedules a task on the server thread - the server manager's tick queue
     * @param membershipWipe drops one player's group memberships - run on the server thread before the
     *                       {@code sessionEnded} of a logout or timeout
     */
    SessionLifecycle(@NotNull Consumer<Runnable> serverThread, @NotNull Consumer<UUID> membershipWipe) {
        this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
        this.membershipWipe = Objects.requireNonNull(membershipWipe, "membershipWipe");
    }

    /**
     * Reports that {@code session} became its player's current session. Any thread; must be called before the
     * session is reachable by anything that could end it, so its end can never be reported ahead of its start.
     */
    void started(@NotNull VoiceServerSession session) {
        transitions.add(new Transition(session, null));
        serverThread.accept(this::drain);
    }

    /**
     * Reports that {@code session} ended by logout or timeout - the two ends that wipe memberships. Any thread.
     * {@link SessionEndReason#REPLACED} is never reported: it is inferred when a newer session starts.
     */
    void ended(@NotNull VoiceServerSession session, @NotNull SessionEndReason reason) {
        if (reason != SessionEndReason.LOGOUT && reason != SessionEndReason.TIMED_OUT)
            throw new IllegalArgumentException("only LOGOUT and TIMED_OUT are reported, got " + reason);

        transitions.add(new Transition(session, reason));
        serverThread.accept(this::drain);
    }

    /**
     * Adds {@code listener}. Any thread: it is activated by the next drain on the server thread, which first
     * replays {@code sessionStarted} for every live session to it alone.
     */
    public @NotNull IListenerRegistration register(@NotNull IVoiceSessionListener listener) {
        Registration registration = new Registration(Objects.requireNonNull(listener, "listener"));
        registrations.add(registration);
        serverThread.accept(this::drain);
        return registration;
    }

    /** The player's live session - announced by {@code sessionStarted}, not yet ended - if any. */
    public @NotNull Optional<IVoiceSession> sessionOf(@NotNull UUID playerUuid) {
        return Optional.ofNullable(liveView.get(Objects.requireNonNull(playerUuid, "playerUuid")));
    }

    /** Every live session, in the order they started. An immutable snapshot. */
    public @NotNull Collection<IVoiceSession> sessions() {
        return liveView.values();
    }

    /**
     * Server thread: activates listeners added since the last drain (replaying the live sessions to them), then
     * delivers every reported transition in order. Scheduled by every report and registration; cheap when idle.
     */
    void drain() {
        activateNewListeners();

        Transition transition;
        while ((transition = transitions.poll()) != null) {
            if (transition.reason == null) {
                start(transition.session);
            } else {
                endReported(transition.session, transition.reason);
            }
        }
    }

    /**
     * Server thread, as the voice server stops: ends every live session with {@link SessionEndReason#SERVER_STOPPED}.
     * The caller drains first - while the group registry still stands - and clears the registry in between, so
     * anything reported since belongs to a session the stop already dropped and is discarded. Listeners stay
     * registered for the next start.
     */
    void serverStopped() {
        transitions.clear();
        for (VoiceServerSession session : new ArrayList<>(live.values())) {
            end(session, SessionEndReason.SERVER_STOPPED);
        }
    }

    private void activateNewListeners() {
        for (Registration registration : registrations) {
            if (registration.active || registration.removed) continue;

            registration.active = true;
            for (VoiceServerSession session : live.values()) {
                if (registration.receives()) notifyStarted(registration, session);
            }
        }
    }

    private void start(VoiceServerSession session) {
        UUID playerUuid = session.getPlayerUuid();
        VoiceServerSession previous = live.get(playerUuid);
        if (previous != null) end(previous, SessionEndReason.REPLACED);

        live.put(playerUuid, session);
        publishLiveView();
        for (Registration registration : registrations) {
            if (registration.receives()) notifyStarted(registration, session);
        }
    }

    private void endReported(VoiceServerSession session, SessionEndReason reason) {
        // Already ended - replaced by a newer session that started first: nothing is owed, and wiping now would
        // hit the newer session's memberships.
        if (live.get(session.getPlayerUuid()) != session) return;

        membershipWipe.accept(session.getPlayerUuid());
        end(session, reason);
    }

    private void end(VoiceServerSession session, SessionEndReason reason) {
        live.remove(session.getPlayerUuid());
        publishLiveView();
        for (Registration registration : registrations) {
            if (registration.receives()) notifyEnded(registration, session, reason);
        }
    }

    private void publishLiveView() {
        liveView = Collections.unmodifiableMap(new LinkedHashMap<UUID, IVoiceSession>(live));
    }

    private void notifyStarted(Registration registration, VoiceServerSession session) {
        try {
            registration.listener.sessionStarted(session);
        } catch (Throwable t) {
            logFailure(registration, "sessionStarted", session, t);
        }
    }

    private void notifyEnded(Registration registration, VoiceServerSession session, SessionEndReason reason) {
        try {
            registration.listener.sessionEnded(session, reason);
        } catch (Throwable t) {
            logFailure(registration, "sessionEnded", session, t);
        }
    }

    private void logFailure(Registration registration, String callback, VoiceServerSession session, Throwable t) {
        if (!LogThrottle.shouldLog(lastFailureLogMillis, FAILURE_LOG_INTERVAL_MILLIS)) return;
        GtnhVoice.LOG.error(
            "Voice session listener {} threw from {} for {}; skipped",
            registration.listener.getClass()
                .getName(),
            callback,
            session.getPlayerName(),
            t);
    }

    /** One reported transition: a start when {@code reason} is null, otherwise an end. */
    private static final class Transition {

        final VoiceServerSession session;
        final SessionEndReason reason;

        Transition(VoiceServerSession session, SessionEndReason reason) {
            this.session = session;
            this.reason = reason;
        }
    }

    private final class Registration implements IListenerRegistration {

        final IVoiceSessionListener listener;
        // Server thread only: set by the drain that replayed the live sessions to this listener.
        boolean active;
        volatile boolean removed;

        Registration(IVoiceSessionListener listener) {
            this.listener = listener;
        }

        boolean receives() {
            return active && !removed;
        }

        @Override
        public void unregister() {
            removed = true;
            registrations.remove(this);
        }
    }
}
