package com.enn3developer.gtnhvoice.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Exercises {@link VoiceSessionListeners} directly against the {@link VoiceSessionListener} contract:
 * dispatch, the self-enforcing started/stopping pairing, and isolation of throwing listeners. No session or
 * network needed - the registry is a plain object, extracted from {@link VoiceClientManager} (a singleton)
 * precisely so the pairing state lives in a fresh instance per test; the fire sites (handleServerHello's
 * success path and the top of closeUdp) are documented on the manager.
 */
class VoiceSessionListenersTest {

    /** Records the event sequence as it arrives, for exact-order assertions. */
    private static final class RecordingListener implements VoiceSessionListener {

        private final List<String> events = new ArrayList<>();

        @Override
        public void sessionStarted() {
            events.add("started");
        }

        @Override
        public void sessionStopping() {
            events.add("stopping");
        }
    }

    @Test
    void startedFiresRegisteredListeners() {
        VoiceSessionListeners registry = new VoiceSessionListeners();
        RecordingListener listener = new RecordingListener();
        registry.add(listener);

        registry.fireSessionStarted();

        assertEquals(Arrays.asList("started"), listener.events);
    }

    @Test
    void stoppingWithoutAPriorStartedIsSilent() {
        VoiceSessionListeners registry = new VoiceSessionListeners();
        RecordingListener listener = new RecordingListener();
        registry.add(listener);

        // closeUdp()'s unconditional-call contexts: fresh connect and repeated disconnects.
        registry.fireSessionStopping();
        registry.fireSessionStopping();

        assertEquals(Arrays.asList(), listener.events);
    }

    @Test
    void startedStoppingSequencesPairCorrectlyAcrossAReconnect() {
        VoiceSessionListeners registry = new VoiceSessionListeners();
        RecordingListener listener = new RecordingListener();
        registry.add(listener);

        registry.fireSessionStarted();
        registry.fireSessionStopping();
        registry.fireSessionStarted();
        registry.fireSessionStopping();

        assertEquals(Arrays.asList("started", "stopping", "started", "stopping"), listener.events);
    }

    @Test
    void doubleStartedWarnsAndDoesNotReFire() {
        VoiceSessionListeners registry = new VoiceSessionListeners();
        RecordingListener listener = new RecordingListener();
        registry.add(listener);

        registry.fireSessionStarted();
        registry.fireSessionStarted();

        assertEquals(Arrays.asList("started"), listener.events);

        // The ignored duplicate must not have disarmed the pairing - the stopping still fires.
        registry.fireSessionStopping();
        assertEquals(Arrays.asList("started", "stopping"), listener.events);
    }

    @Test
    void throwingListenerDoesNotStarveLaterOnes() {
        VoiceSessionListeners registry = new VoiceSessionListeners();
        registry.add(new VoiceSessionListener() {

            @Override
            public void sessionStarted() {
                throw new IllegalStateException("boom");
            }

            @Override
            public void sessionStopping() {
                throw new IllegalStateException("boom");
            }
        });
        RecordingListener listener = new RecordingListener();
        registry.add(listener);

        registry.fireSessionStarted();
        registry.fireSessionStopping();

        assertEquals(Arrays.asList("started", "stopping"), listener.events);
    }

    @Test
    void removedListenerNoLongerHearsEvents() {
        VoiceSessionListeners registry = new VoiceSessionListeners();
        RecordingListener listener = new RecordingListener();
        registry.add(listener);
        registry.remove(listener);

        registry.fireSessionStarted();
        registry.fireSessionStopping();

        assertEquals(Arrays.asList(), listener.events);
    }
}
