package com.enn3developer.gtnhvoice.api.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The server half of the activation-id contract: {@link ActivationIds#of} derives exactly the name-based UUID of
 * {@code "<addonName>:<activationName>"} - the expected values were computed outside the JVM (MD5 of the UTF-8
 * name, version 3 and IETF variant bits set) - so a group's constant matches what the client stamps, and no two
 * name pairs can collide.
 */
class ActivationIdsTest {

    @Test
    void derivesTheNameBasedUuidOfAddonNameColonActivationName() {
        assertEquals(
            UUID.fromString("a0826819-629a-3b48-8215-0553a6c09aea"),
            ActivationIds.of("gtnh-voice-radio", "radio"));
    }

    @Test
    void namesAreCaseSensitive() {
        assertEquals(
            UUID.fromString("708c20a5-bb19-39f1-bb50-7f5e83a6223a"),
            ActivationIds.of("gtnh-voice-radio", "Radio"));
    }

    @Test
    void derivedIdsAreVersion3SoRandomIdsFromOlderClientsCanNeverEqualOne() {
        // Older clients stamped UUID.randomUUID(), always version 4.
        assertEquals(3, ActivationIds.of("gtnh-voice-radio", "radio")
            .version());
    }

    @Test
    void defaultIdIsTheNilUuid() {
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000000"), ActivationIds.DEFAULT);
    }

    @Test
    void addonNameMayContainTheSeparator() {
        assertEquals(UUID.fromString("02cc8f08-398a-3f31-93b5-54e8105ebe4c"), ActivationIds.of("a:b", "c"));
    }

    @Test
    void activationNameMayNotContainTheSeparatorSoTheSplitIsNeverAmbiguous() {
        // "a" + ":" + "b:c" would derive the same id as ("a:b", "c") above.
        assertThrows(IllegalArgumentException.class, () -> ActivationIds.of("a", "b:c"));
    }

    @Test
    void blankAddonNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ActivationIds.of(" ", "radio"));
    }

    @Test
    void blankActivationNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ActivationIds.of("gtnh-voice-radio", ""));
    }

    @Test
    void nullNamesAreRejected() {
        assertThrows(NullPointerException.class, () -> ActivationIds.of(null, "radio"));
        assertThrows(NullPointerException.class, () -> ActivationIds.of("gtnh-voice-radio", null));
    }
}
