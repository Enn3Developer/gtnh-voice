package com.enn3developer.gtnhvoice.client.api;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.api.client.IAudioLifecycleListener;
import com.enn3developer.gtnhvoice.api.client.ICapturePcmFilter;
import com.enn3developer.gtnhvoice.api.client.IPlaybackPcmFilter;

/**
 * The bridging dispatch layer between {@link ClientApiBackend}'s durable bundle storage and the live
 * internals: wires every stored audio bundle onto each fresh per-session {@code PlaybackManager} (a NEW one
 * exists after every connect), keeps mid-session registrations and removals in sync, and wires capture
 * bundles once onto the singleton-durable capture chain. Extracted from the backend behind the two nested
 * target interfaces for exactly the reason {@code VoiceSessionListeners} was: the bookkeeping is unit-testable
 * with recording fakes, no {@code VoiceClientManager} or audio thread required.
 * <p>
 * Why audio and capture differ: audio bundles attach to per-session state, so they are re-wired on every
 * {@code sessionStarted} and their wiring map is simply cleared on {@code sessionStopping} - NO detach calls,
 * because the per-session manager (and every registry on it) dies with the session anyway, and poking a
 * dying manager buys nothing. Capture bundles attach to the durable chain exactly once at registration and
 * detach only at unregistration; session transitions never touch them.
 * <p>
 * Threading: the session callbacks run on the session-transition thread under {@code VoiceClientManager}'s
 * monitor, the bundle hooks on arbitrary addon threads - hence the {@code synchronized} methods. Everything
 * inside the lock is registry adds and audio-thread command submissions, all non-blocking, which keeps the
 * monitor-held session callbacks fast (the {@code VoiceSessionListener} contract). No path here acquires the
 * manager's monitor, so the lock ordering is one-way and deadlock-free.
 */
final class AddonSessionBridge {

    /**
     * The per-session playback attach seams ({@code PlaybackManager}'s API-backing methods in production);
     * handles are the opaque objects those seams return, kept exact per bundle so detach can never miss.
     */
    interface PlaybackSessionTarget {

        Object attachListener(String addonName, IAudioLifecycleListener listener);

        void detachListener(Object handle);

        Object attachPlaybackFilter(String addonName, IPlaybackPcmFilter filter);

        void detachPlaybackFilter(Object handle);
    }

    /** The durable capture attach seams ({@code VoiceClientManager}'s API-backing methods in production). */
    interface CaptureTarget {

        Object attachCaptureFilter(String addonName, ICapturePcmFilter filter);

        void detachCaptureFilter(Object handle);
    }

    /** The handles one wired audio bundle holds on the current session. */
    private static final class BundleWiring {

        @Nullable
        final Object listenerHandle;
        final List<Object> filterHandles;

        BundleWiring(@Nullable Object listenerHandle, List<Object> filterHandles) {
            this.listenerHandle = listenerHandle;
            this.filterHandles = filterHandles;
        }
    }

    private final ClientApiBackend backend;
    private final CaptureTarget captureTarget;

    // Identity maps, deliberately: bundles are value-equal records, and two identical registrations from the
    // same addon must keep independent wiring - storage add/remove is by instance, and so is this.
    private final Map<AudioRegistrationBundle, BundleWiring> sessionWiring = new IdentityHashMap<>();
    private final Map<CaptureRegistrationBundle, List<Object>> captureWiring = new IdentityHashMap<>();

    /** The current session's attach target; {@code null} exactly while no session is live. */
    @Nullable
    private PlaybackSessionTarget liveSession;

    AddonSessionBridge(ClientApiBackend backend, CaptureTarget captureTarget) {
        this.backend = backend;
        this.captureTarget = captureTarget;
    }

    /**
     * Session came up: adopt {@code target} as the live session and wire every stored audio bundle onto it.
     * The listener attach's replay guard makes this correct whether or not the new session's AL context is
     * live yet - see {@code PlaybackManager#attachAddonListener}.
     */
    synchronized void onSessionStarted(PlaybackSessionTarget target) {
        liveSession = target;
        for (AudioRegistrationBundle bundle : backend.audioBundlesView()) {
            wireAudioBundle(bundle);
        }
    }

    /**
     * Session is about to die: drop the wiring map WITHOUT any detach calls - the per-session manager and its
     * registries die with the session, so unwiring would be work spent on a corpse (see the class javadoc).
     */
    synchronized void onSessionStopping() {
        liveSession = null;
        sessionWiring.clear();
    }

    /**
     * Mid-session registration hook ({@link ClientApiBackend#addAudioBundle}): wires the new bundle onto the
     * live session immediately - the same path {@link #onSessionStarted} takes, and the listener attach's
     * replay command delivers the contextCreated/sourceCreated catch-up the public API promises. Storage-only
     * when no session is live; {@link #onSessionStarted} picks the bundle up from the backend's view later.
     */
    synchronized void onAudioBundleAdded(AudioRegistrationBundle bundle) {
        if (liveSession == null) return;
        wireAudioBundle(bundle);
    }

    /**
     * Unregistration hook ({@link ClientApiBackend#removeAudioBundle}): unwires the bundle's exact handles
     * from the live session and forgets them. A bundle with no wiring (no live session, or wired to a session
     * that already stopped) needs nothing beyond the storage removal the backend already did.
     */
    synchronized void onAudioBundleRemoved(AudioRegistrationBundle bundle) {
        BundleWiring wiring = sessionWiring.remove(bundle);
        if (wiring == null) return;

        if (wiring.listenerHandle != null) liveSession.detachListener(wiring.listenerHandle);
        for (Object handle : wiring.filterHandles) {
            liveSession.detachPlaybackFilter(handle);
        }
    }

    /**
     * Registration hook ({@link ClientApiBackend#addCaptureBundle}): wires the bundle's filters onto the
     * durable capture chain exactly once - no session involvement, see the class javadoc.
     */
    synchronized void onCaptureBundleAdded(CaptureRegistrationBundle bundle) {
        List<Object> handles = new ArrayList<>();
        for (ICapturePcmFilter filter : bundle.captureFilters()) {
            handles.add(captureTarget.attachCaptureFilter(bundle.addonName(), filter));
        }
        captureWiring.put(bundle, handles);
    }

    /** Unregistration hook ({@link ClientApiBackend#removeCaptureBundle}): the exact inverse of the add. */
    synchronized void onCaptureBundleRemoved(CaptureRegistrationBundle bundle) {
        List<Object> handles = captureWiring.remove(bundle);
        if (handles == null) return;

        for (Object handle : handles) {
            captureTarget.detachCaptureFilter(handle);
        }
    }

    private void wireAudioBundle(AudioRegistrationBundle bundle) {
        // Double-wire guard: a bundle stored just before sessionStarted iterates the backend's view can also
        // arrive through onAudioBundleAdded - whichever runs second (the lock serializes them) must no-op.
        if (sessionWiring.containsKey(bundle)) return;

        Object listenerHandle = bundle.listener() == null ? null
            : liveSession.attachListener(bundle.addonName(), bundle.listener());
        List<Object> filterHandles = new ArrayList<>();
        for (IPlaybackPcmFilter filter : bundle.playbackFilters()) {
            filterHandles.add(liveSession.attachPlaybackFilter(bundle.addonName(), filter));
        }
        sessionWiring.put(bundle, new BundleWiring(listenerHandle, filterHandles));
    }
}
