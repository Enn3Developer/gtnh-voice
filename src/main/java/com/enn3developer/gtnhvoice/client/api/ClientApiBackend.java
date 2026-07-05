package com.enn3developer.gtnhvoice.client.api;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import com.enn3developer.gtnhvoice.api.client.IClientAudioApi;
import com.enn3developer.gtnhvoice.api.client.IClientCaptureApi;

/**
 * Singleton storage backend for the public client addon API ({@code com.enn3developer.gtnhvoice.api.client}) -
 * the one class {@code GtnhVoiceClientApi} reaches into, mirroring how the server API's entry point reaches
 * {@code VoiceServerManager}. Holds every registered bundle durably for the whole client lifetime: bundles are
 * added by the registration builders' {@code done()}, removed by their {@code IRegistration} handles, and never
 * touched by session transitions - which is exactly what makes registrations survive disconnect/reconnect
 * cycles. CopyOnWrite lists so future hot-path iteration reads stable snapshots while addons register and
 * unregister from arbitrary threads; registration churn is rare.
 * <p>
 * BRIDGING SEAM - implemented by a LATER task; nothing here dispatches yet, this class only stores. That task's
 * dispatch layer will observe session transitions via {@code VoiceClientManager}'s session-listener registry
 * and, for each live session, wire every bundle onto the per-session internal registries: each audio bundle's
 * {@link AudioRegistrationBundle#listener()} onto the session {@code PlaybackManager}'s lifecycle-listener
 * registry (wrapped with per-bundle failure isolation attributed to {@link AudioRegistrationBundle#addonName()},
 * and with the mid-session replay promised by {@code IClientAudioApi#register}), each
 * {@link AudioRegistrationBundle#playbackFilters()} entry onto that manager's PCM filter registry, and each
 * {@link CaptureRegistrationBundle#captureFilters()} entry onto {@code VoiceClientManager}'s capture PCM filter
 * chain. It iterates {@link #audioBundlesView()}/{@link #captureBundlesView()} and hooks
 * {@link #addAudioBundle}/{@link #removeAudioBundle} (and the capture twins) for mid-session wiring/unwiring.
 */
public final class ClientApiBackend {

    private static final ClientApiBackend INSTANCE = new ClientApiBackend();

    private final List<AudioRegistrationBundle> audioBundles = new CopyOnWriteArrayList<>();
    private final List<CaptureRegistrationBundle> captureBundles = new CopyOnWriteArrayList<>();
    private final IClientAudioApi audioApi = new ClientAudioApi(this);
    private final IClientCaptureApi captureApi = new ClientCaptureApi(this);

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
     * Stores an activated audio bundle. Bridging seam: the later dispatch layer hooks this to wire a
     * mid-session registration onto the live session (including the state replay).
     */
    void addAudioBundle(AudioRegistrationBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        audioBundles.add(bundle);
    }

    /**
     * Drops an audio bundle by identity; no-op when already removed - {@code IRegistration}'s idempotence.
     * Bridging seam: the later dispatch layer hooks this to unwire the bundle from the live session.
     */
    void removeAudioBundle(AudioRegistrationBundle bundle) {
        audioBundles.remove(bundle);
    }

    /** Stores an activated capture bundle - see {@link #addAudioBundle} for the bridging-seam role. */
    void addCaptureBundle(CaptureRegistrationBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        captureBundles.add(bundle);
    }

    /** Drops a capture bundle by identity; no-op when already removed - see {@link #removeAudioBundle}. */
    void removeCaptureBundle(CaptureRegistrationBundle bundle) {
        captureBundles.remove(bundle);
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
}
