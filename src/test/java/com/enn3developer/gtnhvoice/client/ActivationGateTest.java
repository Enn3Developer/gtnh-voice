package com.enn3developer.gtnhvoice.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.Config;

/**
 * The per-frame transmit decision with addon activations in play: a keyed activation opens the mic in both
 * activation modes and stamps its own id, hard mute beats everything, precedence goes to the most recently keyed
 * activation, and everything else is ordinary voice under the nil default id. Frames are 20ms of silence (-127dB,
 * far below the -40dB default VA threshold) or of loud speech (about -12dB, far above it); a fresh gate has no VA
 * hangover pending and its PTT key is up, so the activation mode alone never opens on silence.
 */
class ActivationGateTest {

    // Name-based UUIDs of "gtnh-voice-radio:radio" and "gtnh-voice-radio:intercom", computed outside the JVM.
    private static final UUID RADIO = UUID.fromString("a0826819-629a-3b48-8215-0553a6c09aea");
    private static final UUID INTERCOM = UUID.fromString("5a209c0d-a3dd-34ed-9064-0f0bb1e2b72d");
    private static final UUID ORDINARY_VOICE = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final String savedActivationMode = Config.activationMode;
    private final KeyedActivations keyed = new KeyedActivations();
    private boolean muted;
    private final ActivationGate gate = new ActivationGate(keyed, () -> muted);

    @AfterEach
    void restoreActivationMode() {
        Config.activationMode = savedActivationMode;
    }

    private static short[] silence() {
        return new short[960];
    }

    private static short[] speech() {
        short[] frame = new short[960];
        Arrays.fill(frame, (short) 8000);
        return frame;
    }

    @Test
    void silenceStaysClosedInVoiceActivationModeWithNothingKeyed() {
        Config.activationMode = "VOICE_ACTIVATION";

        assertNull(gate.transmitAs(silence()));
    }

    @Test
    void keyedActivationOpensTheMicOnSilenceInVoiceActivationMode() {
        Config.activationMode = "VOICE_ACTIVATION";
        keyed.key(RADIO);

        assertEquals(RADIO, gate.transmitAs(silence()));
    }

    @Test
    void pushToTalkModeStaysClosedWithoutTheKeyWithNothingKeyed() {
        Config.activationMode = "PUSH_TO_TALK";

        assertNull(gate.transmitAs(speech()));
    }

    @Test
    void keyedActivationOpensTheMicWithoutThePushToTalkKey() {
        Config.activationMode = "PUSH_TO_TALK";
        keyed.key(RADIO);

        assertEquals(RADIO, gate.transmitAs(silence()));
    }

    @Test
    void ordinaryVoiceCarriesTheDefaultId() {
        Config.activationMode = "VOICE_ACTIVATION";

        assertEquals(ORDINARY_VOICE, gate.transmitAs(speech()));
    }

    @Test
    void framesSentWhileKeyedCarryTheActivationIdEvenWhenVoiceActivationWouldSendThemAnyway() {
        Config.activationMode = "VOICE_ACTIVATION";
        keyed.key(RADIO);

        assertEquals(RADIO, gate.transmitAs(speech()));
    }

    @Test
    void muteWinsOverAKeyedActivation() {
        Config.activationMode = "PUSH_TO_TALK";
        keyed.key(RADIO);
        muted = true;

        assertNull(gate.transmitAs(silence()));
    }

    @Test
    void muteWinsOverOrdinaryVoice() {
        Config.activationMode = "VOICE_ACTIVATION";
        muted = true;

        assertNull(gate.transmitAs(speech()));
    }

    @Test
    void mostRecentlyKeyedActivationWins() {
        keyed.key(RADIO);
        keyed.key(INTERCOM);

        assertEquals(INTERCOM, gate.transmitAs(silence()));
    }

    @Test
    void unkeyingTheWinnerHandsPrecedenceBackToTheActivationKeyedBeforeIt() {
        keyed.key(RADIO);
        keyed.key(INTERCOM);
        keyed.unkey(INTERCOM);

        assertEquals(RADIO, gate.transmitAs(silence()));
    }

    @Test
    void rekeyingAnActivationThatIsStillKeyedDoesNotTakePrecedenceBack() {
        keyed.key(RADIO);
        keyed.key(INTERCOM);
        keyed.key(RADIO); // e.g. an addon re-asserting its key every tick

        assertEquals(INTERCOM, gate.transmitAs(silence()));
    }

    @Test
    void unkeyingTheLastActivationHandsTheFrameBackToTheActivationMode() {
        Config.activationMode = "VOICE_ACTIVATION";
        keyed.key(RADIO);
        gate.transmitAs(silence());
        keyed.unkey(RADIO);

        assertNull(gate.transmitAs(silence()));
    }

    @Test
    void reportsTransmittingAsTheIdTheLatestFrameCarried() {
        keyed.key(RADIO);

        gate.transmitAs(silence());

        assertTrue(gate.isTransmittingAs(RADIO), "the frame went out as the radio");
        assertFalse(gate.isTransmittingAs(ORDINARY_VOICE), "and as nothing else");
    }

    @Test
    void stopsReportingTransmittingOnceMuted() {
        keyed.key(RADIO);
        gate.transmitAs(silence());

        muted = true; // a muted device produces no further frames, so no new decision clears the old one

        assertFalse(gate.isTransmittingAs(RADIO));
    }

    @Test
    void stopsReportingTransmittingAndSpeakingOnceTheCaptureWorkerStops() {
        keyed.key(RADIO);
        gate.transmitAs(silence());

        gate.captureStopped();

        assertFalse(gate.isTransmittingAs(RADIO), "no frame is going out as the radio any more");
        assertFalse(gate.isSpeaking(), "nor as anything else");
    }
}
