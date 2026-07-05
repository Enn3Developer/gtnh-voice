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

    @Test
    void filtersAccumulateInRegistrationOrder() {
        ICapturePcmFilter first = frame -> frame;
        ICapturePcmFilter second = frame -> frame;
        backend.capture()
            .register("addon")
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
    void registerRejectsNullAndBlankAddonName() {
        assertThrows(
            NullPointerException.class,
            () -> backend.capture()
                .register(null));
        assertThrows(
            IllegalArgumentException.class,
            () -> backend.capture()
                .register(""));
    }

    @Test
    void doneOnAnEmptyBundleThrowsAndStoresNothing() {
        assertThrows(
            IllegalStateException.class,
            () -> backend.capture()
                .register("addon")
                .done());
        assertTrue(backend.captureBundlesView().isEmpty());
    }

    @Test
    void builderIsSingleUseSoASecondDoneThrows() {
        ICaptureRegistrationBuilder builder = backend.capture()
            .register("addon")
            .filter(frame -> frame);
        builder.done();

        assertThrows(IllegalStateException.class, builder::done);
        assertThrows(IllegalStateException.class, () -> builder.filter(frame -> frame));
        assertEquals(1, backend.captureBundlesView().size());
    }

    @Test
    void filterRejectsNull() {
        ICaptureRegistrationBuilder builder = backend.capture()
            .register("addon");

        assertThrows(NullPointerException.class, () -> builder.filter(null));
    }

    @Test
    void unregisterRemovesTheBundleAndIsIdempotent() {
        IRegistration registration = backend.capture()
            .register("addon")
            .filter(frame -> frame)
            .done();

        registration.unregister();
        assertTrue(backend.captureBundlesView().isEmpty());

        registration.unregister();
        registration.close();
        assertTrue(backend.captureBundlesView().isEmpty());
    }
}
