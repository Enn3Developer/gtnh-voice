package com.enn3developer.gtnhvoice.client.api;

import java.util.List;
import java.util.UUID;

import com.enn3developer.gtnhvoice.api.client.IAudioLifecycleListener;

/**
 * Fans one bundle's accumulated parts (whole listeners and per-event callback adapters, in exact builder
 * registration order) out as the single assembled listener {@link AudioRegistrationBundle} stores. Deliberately
 * no per-part isolation: everything in a bundle is one addon's own code, and the dispatch layer isolates per
 * bundle - a part that throws aborts the rest of ITS OWN bundle for that event, nobody else's.
 */
final class CompositeLifecycleListener implements IAudioLifecycleListener {

    private final List<IAudioLifecycleListener> parts;

    CompositeLifecycleListener(List<IAudioLifecycleListener> parts) {
        this.parts = parts;
    }

    @Override
    public void contextCreated(long deviceHandle) {
        for (IAudioLifecycleListener part : parts) part.contextCreated(deviceHandle);
    }

    @Override
    public void contextDestroying() {
        for (IAudioLifecycleListener part : parts) part.contextDestroying();
    }

    @Override
    public void sourceCreated(UUID sourceId, int sourceHandle) {
        for (IAudioLifecycleListener part : parts) part.sourceCreated(sourceId, sourceHandle);
    }

    @Override
    public void sourceDestroying(UUID sourceId, int sourceHandle) {
        for (IAudioLifecycleListener part : parts) part.sourceDestroying(sourceId, sourceHandle);
    }

    @Override
    public void audioTick() {
        for (IAudioLifecycleListener part : parts) part.audioTick();
    }
}
