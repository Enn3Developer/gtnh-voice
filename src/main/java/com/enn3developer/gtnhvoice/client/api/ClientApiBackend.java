package com.enn3developer.gtnhvoice.client.api;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.api.client.IAudioLifecycleListener;
import com.enn3developer.gtnhvoice.api.client.ICapturePcmFilter;
import com.enn3developer.gtnhvoice.api.client.IClientAudioApi;
import com.enn3developer.gtnhvoice.api.client.IClientCaptureApi;
import com.enn3developer.gtnhvoice.api.client.IPlaybackPcmFilter;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.client.source.VoiceSourceManager;

/**
 * Singleton storage backend for the public client addon API ({@code com.enn3developer.gtnhvoice.api.client}) -
 * the one class {@code GtnhVoiceClientApi} reaches into, mirroring how the server API's entry point reaches
 * {@code VoiceServerManager}. Holds every registered bundle durably for the whole client lifetime: bundles are
 * added by the registration builders' {@code done()}, removed by their {@code IRegistration} handles, and never
 * touched by session transitions - which is exactly what makes registrations survive disconnect/reconnect
 * cycles. CopyOnWrite lists so future hot-path iteration reads stable snapshots while addons register and
 * unregister from arbitrary threads; registration churn is rare.
 * <p>
 * Bridging: {@link #initSessionBridging()} (called once from {@code ClientProxy.preInit}) hangs an
 * {@link AddonSessionBridge} off {@code VoiceClientManager}'s session-listener registry; the bridge re-wires
 * every stored audio bundle onto each fresh per-session {@code PlaybackManager} - listeners through
 * {@code PlaybackManager#attachAddonListener} (per-addon isolation plus the race-free state replay), filters
 * through {@code attachAddonPlaybackFilter} - and wires capture bundles once onto the singleton-durable
 * capture chain. The add/remove methods below hook the bridge for mid-session wiring/unwiring; see the bridge
 * for the full audio-vs-capture rationale.
 */
public final class ClientApiBackend {

    private static final ClientApiBackend INSTANCE = new ClientApiBackend();

    private final List<AudioRegistrationBundle> audioBundles = new CopyOnWriteArrayList<>();
    private final List<CaptureRegistrationBundle> captureBundles = new CopyOnWriteArrayList<>();
    private final IClientAudioApi audioApi = new ClientAudioApi(this);
    private final IClientCaptureApi captureApi = new ClientCaptureApi(this);

    private final AtomicBoolean bridgingInitialized = new AtomicBoolean();
    // Null until initSessionBridging (or a test injects a fake-target bridge) - the hooks below skip a null
    // bridge, which is what keeps plain storage tests free of any VoiceClientManager involvement.
    private volatile @Nullable AddonSessionBridge bridge;

    // Package-private so tests exercise a fresh, isolated backend instead of polluting the singleton.
    ClientApiBackend() {}

    public static ClientApiBackend getInstance() {
        return INSTANCE;
    }

    /** The playback-side API facade {@code GtnhVoiceClientApi.audio()} hands to addons. */
    public IClientAudioApi audio() {
        return audioApi;
    }

    /** The capture-side API facade {@code GtnhVoiceClientApi.capture()} hands to addons. */
    public IClientCaptureApi capture() {
        return captureApi;
    }

    /**
     * Idempotently wires the addon-API bridging layer into {@code VoiceClientManager}'s session-listener
     * registry. Called from {@code ClientProxy.preInit}, where the mod's other client singletons are wired:
     * the registration must exist before the FIRST session, because {@code sessionStarted} is the only thing
     * that re-wires durable bundles onto each fresh per-session {@code PlaybackManager}.
     */
    public void initSessionBridging() {
        if (!bridgingInitialized.compareAndSet(false, true)) return;

        AddonSessionBridge newBridge = new AddonSessionBridge(this, new VoiceClientCaptureTarget());
        // Publish the bridge before registering the session listener, so a bundle stored in the gap is at
        // worst hooked with no live session (storage-only, correct) rather than not hooked at all.
        bridge = newBridge;
        VoiceClientManager.getInstance()
            .attachAddonSessionBridge(() -> bridgeSessionStarted(newBridge), newBridge::onSessionStopping);
    }

    /**
     * The sessionStarted half of the bridge registration: resolves the fresh per-session
     * {@code PlaybackManager} (non-null during the callback per the session-listener contract; guarded anyway
     * because skipping a session's wiring beats an NPE inside the session transition) and hands the bridge a
     * target wrapping it.
     */
    private static void bridgeSessionStarted(AddonSessionBridge bridge) {
        VoiceSourceManager sourceManager = VoiceClientManager.getInstance()
            .getVoiceSourceManager();
        if (sourceManager == null) return;

        bridge.onSessionStarted(new PlaybackManagerSessionTarget(sourceManager.getPlaybackManager()));
    }

    // Package-private so bridge tests inject a fake-target bridge into a fresh backend.
    void bridgeForTests(AddonSessionBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Stores an activated audio bundle, then hands it to the bridge, which wires it onto the live session
     * immediately (mid-session registration) or does nothing when no session is up.
     */
    void addAudioBundle(AudioRegistrationBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        audioBundles.add(bundle);

        AddonSessionBridge liveBridge = bridge;
        if (liveBridge != null) liveBridge.onAudioBundleAdded(bundle);
    }

    /**
     * Drops an audio bundle by identity; no-op when already removed - {@code IRegistration}'s idempotence.
     * The bridge then unwires it from the live session, if it was wired to one.
     */
    void removeAudioBundle(AudioRegistrationBundle bundle) {
        audioBundles.remove(bundle);

        AddonSessionBridge liveBridge = bridge;
        if (liveBridge != null) liveBridge.onAudioBundleRemoved(bundle);
    }

    /**
     * Stores an activated capture bundle, then hands it to the bridge, which wires its filters onto the
     * durable capture chain exactly once - capture wiring has no session involvement (see
     * {@link AddonSessionBridge}).
     */
    void addCaptureBundle(CaptureRegistrationBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        captureBundles.add(bundle);

        AddonSessionBridge liveBridge = bridge;
        if (liveBridge != null) liveBridge.onCaptureBundleAdded(bundle);
    }

    /** Drops a capture bundle by identity; the bridge then detaches its filters from the durable chain. */
    void removeCaptureBundle(CaptureRegistrationBundle bundle) {
        captureBundles.remove(bundle);

        AddonSessionBridge liveBridge = bridge;
        if (liveBridge != null) liveBridge.onCaptureBundleRemoved(bundle);
    }

    /**
     * Read-only live view of the stored audio bundles, in registration order - what the later dispatch layer
     * iterates to wire a fresh session.
     */
    List<AudioRegistrationBundle> audioBundlesView() {
        return Collections.unmodifiableList(audioBundles);
    }

    /**
     * Read-only live view of the stored capture bundles, in registration order - what the later dispatch layer
     * iterates to wire a fresh session.
     */
    List<CaptureRegistrationBundle> captureBundlesView() {
        return Collections.unmodifiableList(captureBundles);
    }

    /**
     * Shared {@code register(addonName)} validation: attribution must exist, but it is not a unique key -
     * multiple bundles may share a name, so nothing here checks for duplicates.
     */
    static String validateAddonName(String addonName) {
        Objects.requireNonNull(addonName, "addonName");
        if (addonName.trim()
            .isEmpty()) throw new IllegalArgumentException("addonName must not be blank");
        return addonName;
    }

    /** The real per-session target: the API-backing attach/detach seams on the session's PlaybackManager. */
    private static final class PlaybackManagerSessionTarget implements AddonSessionBridge.PlaybackSessionTarget {

        private final PlaybackManager playback;

        PlaybackManagerSessionTarget(PlaybackManager playback) {
            this.playback = playback;
        }

        @Override
        public Object attachListener(String addonName, IAudioLifecycleListener listener) {
            return playback.attachAddonListener(addonName, listener);
        }

        @Override
        public void detachListener(Object handle) {
            playback.detachAddonListener(handle);
        }

        @Override
        public Object attachPlaybackFilter(String addonName, IPlaybackPcmFilter filter) {
            return playback.attachAddonPlaybackFilter(addonName, filter);
        }

        @Override
        public void detachPlaybackFilter(Object handle) {
            playback.detachAddonPlaybackFilter(handle);
        }
    }

    /** The real durable capture target: the API-backing seams on the VoiceClientManager singleton. */
    private static final class VoiceClientCaptureTarget implements AddonSessionBridge.CaptureTarget {

        @Override
        public Object attachCaptureFilter(String addonName, ICapturePcmFilter filter) {
            return VoiceClientManager.getInstance()
                .attachAddonCaptureFilter(addonName, filter);
        }

        @Override
        public void detachCaptureFilter(Object handle) {
            VoiceClientManager.getInstance()
                .detachAddonCaptureFilter(handle);
        }
    }
}
