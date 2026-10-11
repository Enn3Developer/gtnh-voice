package com.enn3developer.gtnhvoice.client.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.client.IAddonActivation;
import com.enn3developer.gtnhvoice.api.client.IVoiceAddon;

/**
 * {@code IVoiceAddon.activation}: handles are get-or-create per name, carry the name-based id the server derives
 * from the same two names, and key into the backend registry the {@code ActivationGate} reads per frame. Runs on a
 * fresh backend; {@code isTransmitting} asks the real {@code VoiceClientManager} singleton, which never connects in
 * this JVM - the documented no-session state.
 */
class AddonActivationTest {

    // Name-based UUIDs of "gtnh-voice-radio:radio" and "intercom-mod:radio", computed outside the JVM.
    private static final UUID RADIO = UUID.fromString("a0826819-629a-3b48-8215-0553a6c09aea");
    private static final UUID INTERCOM_MODS_RADIO = UUID.fromString("3eab4bdd-700c-3340-bdac-13c9161ad590");

    private final ClientApiBackend backend = new ClientApiBackend();
    private final IVoiceAddon radioAddon = backend.newAddonBuilder("gtnh-voice-radio")
        .register();

    @Test
    void idIsTheNameBasedUuidOfAddonNameColonActivationName() {
        assertEquals(RADIO, radioAddon.activation("radio")
            .id());
    }

    @Test
    void sameActivationNameInAnotherAddonIsADifferentActivation() {
        IVoiceAddon intercomAddon = backend.newAddonBuilder("intercom-mod")
            .register();

        assertEquals(INTERCOM_MODS_RADIO, intercomAddon.activation("radio")
            .id());
    }

    @Test
    void sameNameReturnsTheSameHandle() {
        assertSame(radioAddon.activation("radio"), radioAddon.activation("radio"));
    }

    @Test
    void keyingAnActivationHandsItsIdToTheRegistryTheGateReads() {
        IAddonActivation radio = radioAddon.activation("radio");

        radio.setKeyed(true);

        assertTrue(radio.isKeyed());
        assertEquals(RADIO, backend.keyedActivations()
            .winner());
    }

    @Test
    void unkeyingTakesItBackOut() {
        IAddonActivation radio = radioAddon.activation("radio");
        radio.setKeyed(true);

        radio.setKeyed(false);

        assertFalse(radio.isKeyed());
        assertNull(backend.keyedActivations()
            .winner());
    }

    @Test
    void reassertingTheKeyAfterASessionEndReleasedItKeysItAgain() {
        IAddonActivation radio = radioAddon.activation("radio");
        radio.setKeyed(true);
        backend.keyedActivations()
            .clear(); // what the voice session teardown does

        boolean keyedAfterTheSessionEnded = radio.isKeyed();
        radio.setKeyed(true); // the documented every-tick re-assertion

        assertFalse(keyedAfterTheSessionEnded, "the handle reports the release, it keeps no keyed flag of its own");
        assertEquals(RADIO, backend.keyedActivations()
            .winner(), "re-asserting the key reaches the registry again");
    }

    @Test
    void keyedActivationIsNotTransmittingWithoutAVoiceSession() {
        IAddonActivation radio = radioAddon.activation("radio");

        radio.setKeyed(true);

        assertFalse(radio.isTransmitting());
    }

    @Test
    void blankNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> radioAddon.activation(" "));
    }

    @Test
    void nameContainingTheIdSeparatorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> radioAddon.activation("radio:2"));
    }

    @Test
    void nullNameIsRejected() {
        assertThrows(NullPointerException.class, () -> radioAddon.activation(null));
    }
}
