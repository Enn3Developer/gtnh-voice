package com.enn3developer.gtnhvoice.api.client;

import java.util.UUID;

/**
 * A push-to-talk an addon keys on the player's behalf - a radio item held to talk, an intercom button - obtained
 * from {@link IVoiceAddon#activation(String)}. While ANY activation is keyed the mic transmits regardless of the
 * player's activation mode: no voice-activation threshold, no push-to-talk key. Hard mute still wins: a muted mic
 * never transmits, keyed or not.
 *
 * <pre>{@code
 * IAddonActivation radio = addon.activation("radio");
 * // every client tick:
 * radio.setKeyed(holdingRadio && useKeyDown);
 * // HUD:
 * if (radio.isTransmitting()) drawOnAirLamp();
 * }</pre>
 * <p>
 * Frames carry the activation they were sent under to the server, where groups read it as
 * {@link com.enn3developer.gtnhvoice.api.server.IAudioFrame#getActivationId()}: while keyed, each frame carries this
 * activation's {@link #id()}; every other frame carries
 * {@link com.enn3developer.gtnhvoice.api.server.ActivationIds#DEFAULT}. With several activations keyed at once each
 * frame still carries exactly one id, chosen by precedence: the most recently keyed activation still keyed wins,
 * and when it is unkeyed the one keyed before it takes over again. Re-keying an activation that is already keyed
 * does not change precedence, so re-asserting the key every tick is safe.
 * <p>
 * Durability: the handle lives as long as the client, but keyed state lives only as long as the voice session.
 * When the session ends - disconnect, or a new handshake replacing it (one the server timed out ends at the next
 * connection: the client is not told) - every activation is unkeyed: {@link #isKeyed()} turns false and the
 * precedence order is empty. A new session starts with nothing keyed - keying while no session is up is dropped
 * when the next one starts - so it never transmits under an activation until the addon keys it again. An addon that
 * re-asserts its key every tick, as above, is unaffected; one that forgets to unkey leaves the mic closed on the
 * next connection instead of opening it. Within a session a keyed activation holds the mic open, so still unkey on
 * every path that ends the gesture: release, item switch, opened GUI, death, world unload.
 * <p>
 * Threading: every method is callable from any thread. Implemented by the mod; addons never implement it.
 */
public interface IAddonActivation {

    /**
     * The id frames carry while this activation wins precedence:
     * {@link com.enn3developer.gtnhvoice.api.server.ActivationIds#of ActivationIds.of(addonName, activationName)}
     * over this addon's name and the activation's name - the same value a server-side group derives from the two
     * names, with no handshake involved.
     */
    UUID id();

    /**
     * Keys ({@code true}) or unkeys ({@code false}) this activation, taking effect from the next captured mic frame
     * (frames are 20ms). Keying an activation that is already keyed, or unkeying one that is not, changes nothing.
     * A key lasts until it is unkeyed or the voice session ends - see the class doc.
     */
    void setKeyed(boolean keyed);

    /** Whether this activation is keyed - says nothing about whether it is winning or the mic is sending. */
    boolean isKeyed();

    /**
     * Whether the mic is sending frames stamped with this activation's {@link #id()} right now: it is keyed, wins
     * precedence, a voice session is up and capturing, and the mic is not muted. For UI ("on air"); lags the mic
     * by at most one frame.
     */
    boolean isTransmitting();
}
