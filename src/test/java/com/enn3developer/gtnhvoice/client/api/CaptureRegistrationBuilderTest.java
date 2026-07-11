package com.enn3developer.gtnhvoice.client.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.client.ICapturePcmFilter;
import com.enn3developer.gtnhvoice.api.client.ICaptureRegistrationBuilder;
import com.enn3developer.gtnhvoice.api.client.IRegistration;

/**
 * Capture twin of {@link AudioRegistrationBuilderTest}: builder validation, durable bundle storage in a fresh
 * {@link ClientApiBackend}, and {@link IRegistration} removal for the filters-only capture bundles.
 */
class CaptureRegistrationBuilderTest {

    private final ClientApiBackend backend = new ClientApiBackend();
    private final TestAddons addons = new TestAddons(backend);

    @Test
    void filtersAccumulateInRegistrationOrder() {
        ICapturePcmFilter first = frame -> frame;
        ICapturePcmFilter second = frame -> frame;
        addons.capture("addon")
            .filter(first)
            .filter(second)
            .done();

        assertEquals(1, backend.captureBundlesView().size());
        CaptureRegistrationBundle bundle = backend.captureBundlesView()
            .get(0);
        assertEquals("addon", bundle.addonName());
        assertEquals(Arrays.asList(first, second), bundle.captureFilters());
    }

    @Test
    void addonRegistrationRejectsNullAndBlankName() {
        assertThrows(NullPointerException.class, () -> backend.newAddonBuilder(null));
        assertThrows(IllegalArgumentException.class, () -> backend.newAddonBuilder(""));
    }

    @Test
    void doneOnAnEmptyBundleThrowsAndStoresNothing() {
        assertThrows(
            IllegalStateException.class,
            () -> addons.capture("addon")
                .done());
        assertTrue(backend.captureBundlesView().isEmpty());
    }

    @Test
    void builderIsSingleUseSoASecondDoneThrows() {
        ICaptureRegistrationBuilder builder = addons.capture("addon")
            .filter(frame -> frame);
        builder.done();

        assertThrows(IllegalStateException.class, builder::done);
        assertThrows(IllegalStateException.class, () -> builder.filter(frame -> frame));
        assertEquals(1, backend.captureBundlesView().size());
    }

    @Test
    void filterRejectsNull() {
        ICaptureRegistrationBuilder builder = addons.capture("addon");

        assertThrows(NullPointerException.class, () -> builder.filter(null));
    }

    @Test
    void unregisterRemovesTheBundleAndIsIdempotent() {
        IRegistration registration = addons.capture("addon")
            .filter(frame -> frame)
            .done();

        registration.unregister();
        assertTrue(backend.captureBundlesView().isEmpty());

        registration.unregister();
        registration.close();
        assertTrue(backend.captureBundlesView().isEmpty());
    }
}
