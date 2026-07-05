package com.enn3developer.gtnhvoice.api.client;

import java.util.UUID;

/**
 * Per-event callback form of {@link IAudioLifecycleListener#sourceCreated}/
 * {@link IAudioLifecycleListener#sourceDestroying}, for {@link IAudioRegistrationBuilder#onSourceCreated} and
 * {@link IAudioRegistrationBuilder#onSourceDestroying} - the full threading, pairing and reentrancy contract
 * lives on the listener. A dedicated functional interface rather than a {@code java.util.function} type so the
 * AL source name stays an unboxed {@code int}.
 */
@FunctionalInterface
public interface ISourceEvent {

    /**
     * See {@link IAudioLifecycleListener#sourceCreated} and {@link IAudioLifecycleListener#sourceDestroying}.
     *
     * @param sourceId     the voice source (the speaking player's UUID)
     * @param sourceHandle the AL source name
     */
    void accept(UUID sourceId, int sourceHandle);
}
