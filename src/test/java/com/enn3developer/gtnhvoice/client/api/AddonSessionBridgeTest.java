package com.enn3developer.gtnhvoice.client.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.client.IAudioLifecycleListener;
import com.enn3developer.gtnhvoice.api.client.IPlaybackPcmFilter;
import com.enn3developer.gtnhvoice.api.client.IRegistration;

/**
 * Exercises {@link AddonSessionBridge}'s bookkeeping against recording fake targets, driven through the real
 * {@link ClientApiBackend} storage and builder path - no {@code VoiceClientManager}, {@code PlaybackManager}
 * or audio thread involved, which is exactly why the bridge was extracted behind its target interfaces
 * (mirroring the {@code VoiceSessionListeners} extraction). What is NOT provable here: the full
 * register-connect-events-flow pipeline, which needs real sessions and an AL device - that end-to-end proof
 * belongs to the demo addon and an in-game setup.
 */
class AddonSessionBridgeTest {

    /** One recorded attach: which addon, which registered object, and the handle the fake returned for it. */
    private static final class Attachment {

        final String addonName;
        final Object attached;
        final Object handle = new Object();

        Attachment(String addonName, Object attached) {
            this.addonName = addonName;
            this.attached = attached;
        }
    }

    private static final class RecordingPlaybackTarget implements AddonSessionBridge.PlaybackSessionTarget {

        final List<Attachment> listenerAttaches = new ArrayList<>();
        final List<Attachment> filterAttaches = new ArrayList<>();
        final List<Object> detachedListeners = new ArrayList<>();
        final List<Object> detachedFilters = new ArrayList<>();

        @Override
        public Object attachListener(String addonName, IAudioLifecycleListener listener) {
            Attachment attachment = new Attachment(addonName, listener);
            listenerAttaches.add(attachment);
            return attachment.handle;
        }

        @Override
        public void detachListener(Object handle) {
            detachedListeners.add(handle);
        }

        @Override
        public Object attachPlaybackFilter(String addonName, IPlaybackPcmFilter filter) {
            Attachment attachment = new Attachment(addonName, filter);
            filterAttaches.add(attachment);
            return attachment.handle;
        }

        @Override
        public void detachPlaybackFilter(Object handle) {
            detachedFilters.add(handle);
        }

        int totalCalls() {
            return listenerAttaches.size() + filterAttaches.size() + detachedListeners.size()
                + detachedFilters.size();
        }
    }

    private static final class RecordingCaptureTarget implements AddonSessionBridge.CaptureTarget {

        final List<Attachment> attaches = new ArrayList<>();
        final List<Object> detached = new ArrayList<>();

        @Override
        public Object attachCaptureFilter(String addonName,
            com.enn3developer.gtnhvoice.api.client.ICapturePcmFilter filter) {
            Attachment attachment = new Attachment(addonName, filter);
            attaches.add(attachment);
            return attachment.handle;
        }

        @Override
        public void detachCaptureFilter(Object handle) {
            detached.add(handle);
        }
    }

    private final ClientApiBackend backend = new ClientApiBackend();
    private final RecordingPlaybackTarget playback = new RecordingPlaybackTarget();
    private final RecordingCaptureTarget capture = new RecordingCaptureTarget();
    private final AddonSessionBridge bridge = new AddonSessionBridge(backend, capture);

    AddonSessionBridgeTest() {
        backend.bridgeForTests(bridge);
    }

    private static final IAudioLifecycleListener LISTENER = new IAudioLifecycleListener() {};
    private static final IPlaybackPcmFilter FILTER = (sourceId, frame) -> frame;

    @Test
    void sessionStartedWiresEveryStoredBundle() {
        backend.audio()
            .register("addon-a")
            .lifecycle(LISTENER)
            .playbackFilter(FILTER)
            .done();
        backend.audio()
            .register("addon-b")
            .playbackFilter(FILTER)
            .done();
        assertEquals(0, playback.totalCalls(), "no session yet - registration must touch only storage");

        bridge.onSessionStarted(playback);

        assertEquals(1, playback.listenerAttaches.size());
        assertEquals("addon-a", playback.listenerAttaches.get(0).addonName);
        assertSame(LISTENER, playback.listenerAttaches.get(0).attached);
        assertEquals(2, playback.filterAttaches.size(), "both bundles' filters must be wired");
        assertEquals("addon-b", playback.filterAttaches.get(1).addonName);
    }

    @Test
    void filterOnlyBundleAttachesNoListener() {
        bridge.onSessionStarted(playback);
        backend.audio()
            .register("addon")
            .playbackFilter(FILTER)
            .done();

        assertTrue(playback.listenerAttaches.isEmpty(), "a null bundle listener must never reach the target");
        assertEquals(1, playback.filterAttaches.size());
    }

    @Test
    void midSessionRegistrationWiresImmediately() {
        bridge.onSessionStarted(playback);

        backend.audio()
            .register("addon")
            .lifecycle(LISTENER)
            .done();

        assertEquals(1, playback.listenerAttaches.size());
        assertSame(LISTENER, playback.listenerAttaches.get(0).attached);
    }

    @Test
    void midSessionUnregistrationDetachesExactHandles() {
        bridge.onSessionStarted(playback);
        IRegistration registration = backend.audio()
            .register("addon")
            .lifecycle(LISTENER)
            .playbackFilter(FILTER)
            .done();

        registration.unregister();

        assertEquals(1, playback.detachedListeners.size());
        assertSame(playback.listenerAttaches.get(0).handle, playback.detachedListeners.get(0));
        assertEquals(1, playback.detachedFilters.size());
        assertSame(playback.filterAttaches.get(0).handle, playback.detachedFilters.get(0));
    }

    @Test
    void unregistrationWithoutSessionTouchesOnlyStorage() {
        IRegistration registration = backend.audio()
            .register("addon")
            .lifecycle(LISTENER)
            .done();

        registration.unregister();

        assertEquals(0, playback.totalCalls());
        assertTrue(
            backend.audioBundlesView()
                .isEmpty());
    }

    @Test
    void sessionStoppingClearsWiringWithoutDetachCalls() {
        bridge.onSessionStarted(playback);
        IRegistration registration = backend.audio()
            .register("addon")
            .lifecycle(LISTENER)
            .playbackFilter(FILTER)
            .done();

        bridge.onSessionStopping();
        // Unregistering AFTER the session died must not detach either - the wiring map was cleared, and the
        // per-session manager those handles pointed into is gone.
        registration.unregister();

        assertTrue(playback.detachedListeners.isEmpty(), "sessionStopping must never unwire a dying manager");
        assertTrue(playback.detachedFilters.isEmpty());
    }

    @Test
    void reconnectRewiresStoredBundlesOntoTheFreshTarget() {
        backend.audio()
            .register("addon")
            .lifecycle(LISTENER)
            .done();

        bridge.onSessionStarted(playback);
        bridge.onSessionStopping();
        RecordingPlaybackTarget secondSession = new RecordingPlaybackTarget();
        bridge.onSessionStarted(secondSession);

        assertEquals(1, playback.listenerAttaches.size());
        assertEquals(1, secondSession.listenerAttaches.size(), "a durable bundle must re-wire on every session");
    }

    @Test
    void doubleWireOfOneBundleIsGuarded() {
        // The stored-just-before-sessionStarted race: the bundle arrives both through the backend's view
        // iteration and through the add hook - whichever runs second must no-op.
        AudioRegistrationBundle bundle = new AudioRegistrationBundle("addon", LISTENER, Collections.emptyList());
        bridge.onSessionStarted(playback);

        bridge.onAudioBundleAdded(bundle);
        bridge.onAudioBundleAdded(bundle);

        assertEquals(1, playback.listenerAttaches.size(), "one stored bundle instance must wire exactly once");
    }

    @Test
    void captureBundlesWireOnceAtRegistrationWithoutAnySession() {
        IRegistration registration = backend.capture()
            .register("addon")
            .filter(frame -> frame)
            .done();

        assertEquals(1, capture.attaches.size(), "capture wiring is session-free: the chain is durable");
        assertEquals("addon", capture.attaches.get(0).addonName);

        bridge.onSessionStarted(playback);
        bridge.onSessionStopping();
        assertEquals(1, capture.attaches.size(), "session transitions must never touch capture wiring");
        assertTrue(capture.detached.isEmpty());

        registration.unregister();
        assertEquals(1, capture.detached.size());
        assertSame(capture.attaches.get(0).handle, capture.detached.get(0));
    }
}
