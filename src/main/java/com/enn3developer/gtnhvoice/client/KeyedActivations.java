package com.enn3developer.gtnhvoice.client;

import java.util.Arrays;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The addon activations currently keyed (by id - see {@code ActivationIds}), in the order they were keyed: the
 * input {@link ActivationGate} reads per frame to decide whether, and under which id, the mic transmits. The
 * {@link #winner()} is the most recently keyed activation still keyed; unkeying it hands precedence back to the one
 * keyed before it. Keying an activation that is already keyed changes nothing, so an addon re-asserting its key
 * every tick never steals precedence back from an activation keyed after it.
 * <p>
 * Session-scoped: {@link VoiceClientManager}'s teardown of the voice link {@link #clear()}s it (through
 * {@link ActivationGate#releaseActivations()}) when a session ends and again right before a new one starts, so no
 * key outlives its session and none made between sessions carries into the next. Writers
 * ({@code IAddonActivation.setKeyed}, any thread; the teardown) swap a fresh copy-on-write array under this
 * object's monitor; the capture worker reads one volatile reference per frame - no lock and no allocation on its
 * path.
 */
public final class KeyedActivations {

    private static final UUID[] NONE = new UUID[0];

    // Keying order: the last element is the winner.
    private volatile UUID[] keyed = NONE;

    /** Keys {@code activationId} as the newest winner; no-op (precedence unchanged) when it is already keyed. */
    public synchronized void key(@NotNull UUID activationId) {
        UUID[] current = keyed;
        if (indexOf(current, activationId) >= 0) return;

        UUID[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = activationId;
        keyed = next;
    }

    /** Unkeys {@code activationId}; no-op when it is not keyed. */
    public synchronized void unkey(@NotNull UUID activationId) {
        UUID[] current = keyed;
        int index = indexOf(current, activationId);
        if (index < 0) return;

        UUID[] next = new UUID[current.length - 1];
        System.arraycopy(current, 0, next, 0, index);
        System.arraycopy(current, index + 1, next, index, next.length - index);
        keyed = next;
    }

    /** Unkeys every activation at once, emptying the precedence order. */
    public synchronized void clear() {
        keyed = NONE;
    }

    public boolean isKeyed(@NotNull UUID activationId) {
        return indexOf(keyed, activationId) >= 0;
    }

    /** The most recently keyed activation still keyed, or {@code null} when none is. Lock- and allocation-free. */
    public @Nullable UUID winner() {
        UUID[] current = keyed;
        return current.length == 0 ? null : current[current.length - 1];
    }

    private static int indexOf(UUID[] ids, UUID activationId) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(activationId)) return i;
        }
        return -1;
    }
}
