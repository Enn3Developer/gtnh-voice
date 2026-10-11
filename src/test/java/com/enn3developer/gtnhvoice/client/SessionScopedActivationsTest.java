package com.enn3developer.gtnhvoice.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.Config;

/**
 * Keyed addon activations last one voice session: the manager's real teardown of the voice link releases every key,
 * and the next session's capture worker - which asks the same gate, bound once for every session - sends nothing
 * under an activation until the addon keys it again. The manager is a fresh one that never connects: a real session
 * start needs a UDP socket and OpenAL, and the teardown it runs first is the one {@code onDisconnected} drives here.
 * Push-to-talk mode with the key up, so the activation mode alone never sends a frame - only an activation can.
 */
class SessionScopedActivationsTest {

    // Name-based UUIDs of "gtnh-voice-radio:radio" and "gtnh-voice-radio:intercom", computed outside the JVM.
    private static final UUID RADIO = UUID.fromString("a0826819-629a-3b48-8215-0553a6c09aea");
    private static final UUID INTERCOM = UUID.fromString("5a209c0d-a3dd-34ed-9064-0f0bb1e2b72d");

    private final String savedActivationMode = Config.activationMode;
    private final KeyedActivations keyed = new KeyedActivations();
    private final ActivationGate gate = new ActivationGate(keyed, () -> false);
    private final VoiceClientManager manager = new VoiceClientManager();

    @BeforeEach
    void bindTheGateInPushToTalkModeWithTheKeyUp() {
        Config.activationMode = "PUSH_TO_TALK";
        manager.bindActivationGate(gate);
    }

    @AfterEach
    void restoreActivationMode() {
        Config.activationMode = savedActivationMode;
    }

    /** 20ms of loud speech: voice activation would send it, so only the mode's closed PTT key holds it back. */
    private static short[] speech() {
        short[] frame = new short[960];
        Arrays.fill(frame, (short) 8000);
        return frame;
    }

    @Test
    void nextSessionSendsNoFrameUnderTheActivationUntilTheAddonKeysItAgain() {
        keyed.key(RADIO);
        assertEquals(RADIO, gate.transmitAs(speech()), "keyed, the first session sends under the radio");

        manager.onDisconnected();

        assertFalse(keyed.isKeyed(RADIO), "the session end unkeyed it");
        assertNull(gate.transmitAs(speech()), "the next session's first frame is not sent");
        assertNull(gate.transmitAs(speech()), "nor its second");

        keyed.key(RADIO);

        assertEquals(RADIO, gate.transmitAs(speech()), "keyed again, frames go out under the radio");
    }

    @Test
    void sessionEndUnkeysEveryActivationNotJustTheWinner() {
        keyed.key(RADIO);
        keyed.key(INTERCOM);

        manager.onDisconnected();

        assertNull(gate.transmitAs(speech()));
    }
}
