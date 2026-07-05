package com.enn3developer.gtnhvoice.api.client;

/**
 * Per-event callback form of {@link IAudioLifecycleListener#contextCreated}, for
 * {@link IAudioRegistrationBuilder#onContextCreated} - the full threading, pairing and reentrancy contract lives
 * on the listener. A dedicated functional interface rather than a {@code java.util.function} type so the device
 * handle keeps its domain name and stays an unboxed {@code long}.
 */
@FunctionalInterface
public interface IContextCreated {

    /**
     * See {@link IAudioLifecycleListener#contextCreated}.
     *
     * @param deviceHandle the ALC device handle the live context was created on
     */
    void accept(long deviceHandle);
}
