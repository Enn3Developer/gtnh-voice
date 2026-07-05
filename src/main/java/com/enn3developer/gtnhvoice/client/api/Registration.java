package com.enn3developer.gtnhvoice.client.api;

import java.util.concurrent.atomic.AtomicBoolean;

import com.enn3developer.gtnhvoice.api.client.IRegistration;

/**
 * The one handle per registration bundle - runs its removal action exactly once, no matter how many threads
 * race {@link #unregister()}/{@code close()}. The removal action is a plain {@code Runnable} so this class
 * stays agnostic of which bundle list (audio or capture) it detaches from.
 */
final class Registration implements IRegistration {

    private final AtomicBoolean unregistered = new AtomicBoolean();
    private final Runnable removal;

    Registration(Runnable removal) {
        this.removal = removal;
    }

    @Override
    public void unregister() {
        if (!unregistered.compareAndSet(false, true)) return;
        removal.run();
    }
}
