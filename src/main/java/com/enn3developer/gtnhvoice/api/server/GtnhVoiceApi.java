package com.enn3developer.gtnhvoice.api.server;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.api.server.group.IGroupManager;
import com.enn3developer.gtnhvoice.server.VoiceServerManager;

/**
 * Static entry point addons use to reach the voice server's group routing and session lifecycle - the single place
 * this package touches the server internals, so addons never import them. Server side only, like everything in
 * this package.
 */
public final class GtnhVoiceApi {

    private GtnhVoiceApi() {}

    /**
     * The live {@link IGroupManager}: register groups and drive membership through it. Server side only - it
     * exists on dedicated and integrated servers, never on a pure client.
     */
    public static IGroupManager groupManager() {
        return VoiceServerManager.getInstance()
            .getGroupManager();
    }

    /**
     * Adds a session lifecycle listener - see {@link IVoiceSessionListener} for the threading, ordering, replay and
     * durability contract. Callable from any thread and at any time, including before the voice server first
     * starts; the listener stays registered across voice server restarts until its handle is unregistered.
     *
     * @return the handle that removes the listener
     * @throws NullPointerException if {@code listener} is null
     */
    public static @NotNull IListenerRegistration addSessionListener(@NotNull IVoiceSessionListener listener) {
        return VoiceServerManager.getInstance()
            .getSessionLifecycle()
            .register(listener);
    }

    /**
     * {@code playerUuid}'s current voice session, as session listeners know it: present from its
     * {@code sessionStarted} until its {@code sessionEnded}, empty otherwise. Call on the server thread, where the
     * answer always agrees with the events delivered so far; elsewhere it is safe but only as fresh as the last
     * delivered event.
     */
    public static @NotNull Optional<IVoiceSession> session(@NotNull UUID playerUuid) {
        return VoiceServerManager.getInstance()
            .getSessionLifecycle()
            .sessionOf(playerUuid);
    }

    /**
     * Every live voice session, as session listeners know them, in the order they started - an immutable snapshot.
     * Same threading note as {@link #session(UUID)}.
     */
    public static @NotNull Collection<IVoiceSession> sessions() {
        return VoiceServerManager.getInstance()
            .getSessionLifecycle()
            .sessions();
    }
}
