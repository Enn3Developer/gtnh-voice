package com.enn3developer.gtnhvoice.client.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.client.GtnhVoiceClient;
import com.enn3developer.gtnhvoice.api.client.IVoiceAddon;

/**
 * Exercises the two live queries through the real {@link GtnhVoiceClient} entry point against the real
 * {@code VoiceClientManager} singleton, which - never having connected in this JVM - is exactly the documented
 * no-session state: {@code runOnAudioThread} rejects, {@code sourceMetadata} is empty, and null arguments fail
 * identically to when a session is up. One static handle: the entry point hits the singleton backend, where an
 * addon name is claimed for the JVM lifetime.
 */
class VoiceAddonNoSessionTest {

    private static final IVoiceAddon ADDON = GtnhVoiceClient.addon("no-session-test")
        .description("live-query no-session coverage")
        .register();

    @Test
    void runOnAudioThreadReturnsFalseWithoutASession() {
        assertFalse(ADDON.runOnAudioThread(() -> {}));
    }

    @Test
    void sourceMetadataIsEmptyWithoutASession() {
        assertEquals(Optional.empty(), ADDON.sourceMetadata(UUID.randomUUID()));
    }

    @Test
    void runOnAudioThreadRejectsNullEvenWithoutASession() {
        assertThrows(NullPointerException.class, () -> ADDON.runOnAudioThread(null));
    }

    @Test
    void sourceMetadataRejectsNullEvenWithoutASession() {
        assertThrows(NullPointerException.class, () -> ADDON.sourceMetadata(null));
    }
}
