package com.enn3developer.gtnhvoice.client.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.client.GtnhVoiceClientApi;

/**
 * Exercises the two live queries through the real {@link GtnhVoiceClientApi} entry point against the real
 * {@code VoiceClientManager} singleton, which - never having connected in this JVM - is exactly the documented
 * no-session state: {@code runOnAudioThread} rejects, {@code sourceMetadata} is empty, and null arguments fail
 * identically to when a session is up.
 */
class ClientAudioApiNoSessionTest {

    @Test
    void runOnAudioThreadReturnsFalseWithoutASession() {
        assertFalse(
            GtnhVoiceClientApi.audio()
                .runOnAudioThread(() -> {}));
    }

    @Test
    void sourceMetadataIsEmptyWithoutASession() {
        assertEquals(
            Optional.empty(),
            GtnhVoiceClientApi.audio()
                .sourceMetadata(UUID.randomUUID()));
    }

    @Test
    void runOnAudioThreadRejectsNullEvenWithoutASession() {
        assertThrows(
            NullPointerException.class,
            () -> GtnhVoiceClientApi.audio()
                .runOnAudioThread(null));
    }

    @Test
    void sourceMetadataRejectsNullEvenWithoutASession() {
        assertThrows(
            NullPointerException.class,
            () -> GtnhVoiceClientApi.audio()
                .sourceMetadata(null));
    }
}
