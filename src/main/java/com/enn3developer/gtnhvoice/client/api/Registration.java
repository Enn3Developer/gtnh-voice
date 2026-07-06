package com.enn3developer.gtnhvoice.client.api;

import java.util.concurrent.atomic.AtomicBoolean;

import com.enn3developer.gtnhvoice.api.client.IRegistration;

/**
 * The one handle per registration bundle - runs its removal action exactly once, no matter how many threads
 * race {@link #unregister()}/{@code close()}. The removal action is a plain {@code Runnable} so this class
 * stays agnostic of which bundle list (audio or capture) it detaches from.
 * <p>
 * It also fronts the bundle's {@link FilterGate}: {@link #setFilterEnabled(boolean)}/{@link #isFilterEnabled()}
 * flip and read the one volatile flag every one of the bundle's gated filters shares, from any thread. Once
 * unregistered the gate is inert - a set is a no-op and a read reports {@code false} - so a handle behaves
 * consistently after removal, matching {@link #unregister()}'s idempotence.
 */
final class Registration implements IRegistration {

    private final AtomicBoolean unregistered = new AtomicBoolean();
    private final FilterGate gate;
    private final Runnable removal;

    Registration(FilterGate gate, Runnable removal) {
        this.gate = gate;
        this.removal = removal;
    }

    @Override
    public void unregister() {
        if (!unregistered.compareAndSet(false, true)) return;
        removal.run();
    }

    @Override
    public void setFilterEnabled(boolean enabled) {
        if (unregistered.get()) return;
        gate.setEnabled(enabled);
    }

    @Override
    public boolean isFilterEnabled() {
        return !unregistered.get() && gate.isEnabled();
    }
}
