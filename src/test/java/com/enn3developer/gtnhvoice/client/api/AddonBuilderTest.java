package com.enn3developer.gtnhvoice.client.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.client.IAddonBuilder;
import com.enn3developer.gtnhvoice.api.client.IVoiceAddon;

/**
 * Exercises addon registration against a fresh {@link ClientApiBackend}: the builder's description scalar and
 * single-use rule, the name's shape validation and uniqueness claim, and that only {@code register()} - never
 * an abandoned builder - claims a name.
 */
class AddonBuilderTest {

    private final ClientApiBackend backend = new ClientApiBackend();

    @Test
    void registerReturnsHandleCarryingNameAndDescription() {
        IVoiceAddon addon = backend.newAddonBuilder("MyAddon")
            .description("This is my addon")
            .register();

        assertEquals("MyAddon", addon.name());
        assertEquals(Optional.of("This is my addon"), addon.description());
    }

    @Test
    void descriptionIsEmptyWhenNeverSet() {
        assertEquals(
            Optional.empty(),
            backend.newAddonBuilder("addon")
                .register()
                .description());
    }

    @Test
    void descriptionIsAScalarWhereTheLastCallWins() {
        IVoiceAddon addon = backend.newAddonBuilder("addon")
            .description("first")
            .description("second")
            .register();

        assertEquals(Optional.of("second"), addon.description());
    }

    @Test
    void descriptionRejectsNullAndBlank() {
        IAddonBuilder builder = backend.newAddonBuilder("addon");

        assertThrows(NullPointerException.class, () -> builder.description(null));
        assertThrows(IllegalArgumentException.class, () -> builder.description("   "));
    }

    @Test
    void duplicateNameThrowsOnRegister() {
        backend.newAddonBuilder("addon")
            .register();

        assertThrows(
            IllegalStateException.class,
            () -> backend.newAddonBuilder("addon")
                .register());
    }

    @Test
    void builderIsSingleUseAfterRegister() {
        IAddonBuilder builder = backend.newAddonBuilder("addon");
        builder.register();

        assertThrows(IllegalStateException.class, builder::register);
        assertThrows(IllegalStateException.class, () -> builder.description("late"));
    }

    @Test
    void abandonedBuilderClaimsNoName() {
        backend.newAddonBuilder("addon"); // never registered - must not reserve the name

        assertEquals(
            "addon",
            backend.newAddonBuilder("addon")
                .register()
                .name());
    }
}
