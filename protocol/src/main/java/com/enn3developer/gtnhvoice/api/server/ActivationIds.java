package com.enn3developer.gtnhvoice.api.server;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/**
 * The activation ids every mic frame carries ({@link IAudioFrame#getActivationId()}), shared by both sides: the
 * client stamps them, server groups compare against them. A frame is either ordinary voice - voice activation or
 * the player's push-to-talk key - stamped {@link #DEFAULT}, or sent while a client addon keyed one of its
 * activations ({@code IVoiceAddon.activation(name)}), stamped {@link #of of(addonName, activationName)}.
 * <p>
 * Ids are derived, never registered: the server computes the same id the client stamps from the two names alone,
 * with no handshake and no shared state, so a server-side group can recognize a client addon's activation by
 * constant:
 *
 * <pre>{@code
 * private static final UUID RADIO = ActivationIds.of("gtnh-voice-radio", "radio");
 *
 * public void route(RoutingContext context) {
 *     if (!RADIO.equals(context.getAudio().getActivationId())) return; // ordinary voice: lower groups route it
 *     context.exclusive();
 *     context.getSessionsForGroup()...
 * }
 * }</pre>
 * <p>
 * The derivation is part of the wire contract and never changes within a protocol version: an id is
 * {@code UUID.nameUUIDFromBytes((addonName + ":" + activationName).getBytes(UTF_8))} - a name-based (version 3)
 * UUID over the exact, case-sensitive names. {@link #DEFAULT} is the nil UUID (version 0) and clients that
 * predate activations sent random (version 4) ids, so neither can ever equal a derived id. All methods are pure
 * and safe from any thread.
 */
public final class ActivationIds {

    private ActivationIds() {}

    /** The id of every frame not sent under an addon activation: the nil UUID. */
    public static final UUID DEFAULT = new UUID(0L, 0L);

    /**
     * The id frames carry while the client addon {@code addonName} has its activation {@code activationName}
     * keyed - see the class doc for the exact derivation. Deterministic: the same names give the same id on every
     * client and server, in every session.
     *
     * @param addonName      the addon's registered name, exactly as passed to {@code GtnhVoiceClient.addon}
     * @param activationName the activation's name within that addon
     * @return the derived name-based id; never {@link #DEFAULT}
     * @throws NullPointerException     if either name is null
     * @throws IllegalArgumentException if either name is blank, or {@code activationName} contains {@code ':'} -
     *                                  the separator, banned there so no two name pairs can derive the same id
     */
    public static @NotNull UUID of(@NotNull String addonName, @NotNull String activationName) {
        Objects.requireNonNull(addonName, "addonName");
        Objects.requireNonNull(activationName, "activationName");
        if (addonName.trim()
            .isEmpty()) throw new IllegalArgumentException("addonName must not be blank");
        if (activationName.trim()
            .isEmpty()) throw new IllegalArgumentException("activationName must not be blank");
        if (activationName.indexOf(':') >= 0)
            throw new IllegalArgumentException("activationName must not contain ':': " + activationName);

        return UUID.nameUUIDFromBytes((addonName + ':' + activationName).getBytes(StandardCharsets.UTF_8));
    }
}
