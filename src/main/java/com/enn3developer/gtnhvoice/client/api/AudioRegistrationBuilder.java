package com.enn3developer.gtnhvoice.client.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.api.client.IAudioLifecycleListener;
import com.enn3developer.gtnhvoice.api.client.IAudioRegistrationBuilder;
import com.enn3developer.gtnhvoice.api.client.IContextCreated;
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

    private final ClientApiBackend backend;
    private final String addonName;
    private final List<IAudioLifecycleListener> listenerParts = new ArrayList<>();
    private final List<IPlaybackPcmFilter> playbackFilters = new ArrayList<>();
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
    public IRegistration done() {
        requireNotConsumed();
        if (listenerParts.isEmpty() && playbackFilters.isEmpty()) throw new IllegalStateException(
            "empty registration for '" + addonName + "': add a listener, callback or filter before done()");
        consumed = true;

        AudioRegistrationBundle bundle = new AudioRegistrationBundle(
            addonName,
            assembleListener(),
            Collections.unmodifiableList(new ArrayList<>(playbackFilters)));
        backend.addAudioBundle(bundle);
        return new Registration(() -> backend.removeAudioBundle(bundle));
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
