package com.enn3developer.gtnhvoice.client.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.api.client.IAudioLifecycleListener;
import com.enn3developer.gtnhvoice.api.client.IAudioRegistrationBuilder;
import com.enn3developer.gtnhvoice.api.client.IContextCreated;
import com.enn3developer.gtnhvoice.api.client.IPcmChain;
import com.enn3developer.gtnhvoice.api.client.IPlaybackPcmFilter;
import com.enn3developer.gtnhvoice.api.client.IRegistration;
import com.enn3developer.gtnhvoice.api.client.ISourceEvent;

/**
 * The one {@code IAudioRegistrationBuilder} implementation. Per-event callbacks are adapted into single-method
 * {@code IAudioLifecycleListener} overrides as they arrive, so one list preserves exact registration order
 * across whole listeners and callbacks alike; {@link #done()} collapses that list into the bundle's single
 * assembled listener (unwrapped when there is exactly one part, {@link CompositeLifecycleListener} otherwise).
 * Not thread-safe - a builder is a short-lived, single-caller object; only the activated bundle is shared.
 */
final class AudioRegistrationBuilder implements IAudioRegistrationBuilder {

    private static final int MAX_AUXILIARY_SENDS = 8;

    private final ClientApiBackend backend;
    private final String addonName;
    private final List<IAudioLifecycleListener> listenerParts = new ArrayList<>();
    private final List<IPlaybackPcmFilter> playbackFilters = new ArrayList<>();
    private final List<ChainPlaybackFilter> chainFilters = new ArrayList<>();
    private int auxiliarySends;
    private boolean initiallyEnabled = true;
    private boolean consumed;

    AudioRegistrationBuilder(ClientApiBackend backend, String addonName) {
        this.backend = backend;
        this.addonName = addonName;
    }

    @Override
    public IAudioRegistrationBuilder lifecycle(@NotNull IAudioLifecycleListener listener) {
        requireNotConsumed();
        Objects.requireNonNull(listener, "listener");
        listenerParts.add(listener);
        return this;
    }

    @Override
    public IAudioRegistrationBuilder onContextCreated(@NotNull IContextCreated callback) {
        requireNotConsumed();
        Objects.requireNonNull(callback, "callback");
        listenerParts.add(new IAudioLifecycleListener() {

            @Override
            public void contextCreated(long deviceHandle) {
                callback.accept(deviceHandle);
            }
        });
        return this;
    }

    @Override
    public IAudioRegistrationBuilder onContextDestroying(@NotNull Runnable callback) {
        requireNotConsumed();
        Objects.requireNonNull(callback, "callback");
        listenerParts.add(new IAudioLifecycleListener() {

            @Override
            public void contextDestroying() {
                callback.run();
            }
        });
        return this;
    }

    @Override
    public IAudioRegistrationBuilder onSourceCreated(@NotNull ISourceEvent callback) {
        requireNotConsumed();
        Objects.requireNonNull(callback, "callback");
        listenerParts.add(new IAudioLifecycleListener() {

            @Override
            public void sourceCreated(UUID sourceId, int sourceHandle) {
                callback.accept(sourceId, sourceHandle);
            }
        });
        return this;
    }

    @Override
    public IAudioRegistrationBuilder onSourceDestroying(@NotNull ISourceEvent callback) {
        requireNotConsumed();
        Objects.requireNonNull(callback, "callback");
        listenerParts.add(new IAudioLifecycleListener() {

            @Override
            public void sourceDestroying(UUID sourceId, int sourceHandle) {
                callback.accept(sourceId, sourceHandle);
            }
        });
        return this;
    }

    @Override
    public IAudioRegistrationBuilder onAudioTick(@NotNull Runnable callback) {
        requireNotConsumed();
        Objects.requireNonNull(callback, "callback");
        listenerParts.add(new IAudioLifecycleListener() {

            @Override
            public void audioTick() {
                callback.run();
            }
        });
        return this;
    }

    @Override
    public IAudioRegistrationBuilder playbackFilter(@NotNull IPlaybackPcmFilter filter) {
        requireNotConsumed();
        Objects.requireNonNull(filter, "filter");
        playbackFilters.add(filter);
        return this;
    }

    @Override
    public IAudioRegistrationBuilder playbackChain(@NotNull Consumer<IPcmChain> spec) {
        requireNotConsumed();
        Objects.requireNonNull(spec, "spec");
        PcmChainRecorder recorder = new PcmChainRecorder();
        spec.accept(recorder);
        // compile() throws IllegalArgumentException on an empty spec - fail fast at the call site.
        ChainPlaybackFilter chainFilter = new ChainPlaybackFilter(recorder.compile());
        playbackFilters.add(chainFilter);
        // The chain's per-source pipeline cleanup is a MOD-owned concern, kept out of the addon's listener parts
        // on purpose: the bridge wires it off its own isolated ChainPlaybackCleanupListener, so a throwing addon
        // lifecycle part can never abort the eviction and leak pipelines. Just record the filter here.
        chainFilters.add(chainFilter);
        return this;
    }

    @Override
    public IAudioRegistrationBuilder initiallyEnabled(boolean enabled) {
        requireNotConsumed();
        // Per-bundle scalar, last call wins - not an accumulating listener/filter.
        initiallyEnabled = enabled;
        return this;
    }

    @Override
    public IAudioRegistrationBuilder auxiliarySends(int sends) {
        requireNotConsumed();
        if (sends < 1 || sends > MAX_AUXILIARY_SENDS) throw new IllegalArgumentException(
            "auxiliarySends for '" + addonName + "' must be in [1, " + MAX_AUXILIARY_SENDS + "], got " + sends);
        // Repeated calls keep the strongest requirement, not the last one (see the interface contract).
        auxiliarySends = Math.max(auxiliarySends, sends);
        return this;
    }

    @Override
    public IRegistration done() {
        requireNotConsumed();
        if (listenerParts.isEmpty() && playbackFilters.isEmpty() && auxiliarySends == 0)
            throw new IllegalStateException(
                "empty registration for '" + addonName
                    + "': add a listener, callback, filter or auxiliarySends before done()");
        consumed = true;

        FilterGate gate = new FilterGate(initiallyEnabled);
        AudioRegistrationBundle bundle = new AudioRegistrationBundle(
            addonName,
            assembleListener(),
            Collections.unmodifiableList(new ArrayList<>(playbackFilters)),
            auxiliarySends,
            Collections.unmodifiableList(new ArrayList<>(chainFilters)),
            gate);
        backend.addAudioBundle(bundle);
        return new Registration(gate, () -> backend.removeAudioBundle(bundle));
    }

    private IAudioLifecycleListener assembleListener() {
        if (listenerParts.isEmpty()) return null;
        if (listenerParts.size() == 1) return listenerParts.get(0);
        return new CompositeLifecycleListener(new ArrayList<>(listenerParts));
    }

    private void requireNotConsumed() {
        if (consumed) throw new IllegalStateException(
            "registration builder for '" + addonName + "' is single-use and was already done() - open a new one");
    }
}
