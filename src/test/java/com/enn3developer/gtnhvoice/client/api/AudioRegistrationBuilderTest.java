package com.enn3developer.gtnhvoice.client.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.client.IAudioLifecycleListener;
import com.enn3developer.gtnhvoice.api.client.IAudioRegistrationBuilder;
import com.enn3developer.gtnhvoice.api.client.IPlaybackPcmFilter;
import com.enn3developer.gtnhvoice.api.client.IRegistration;

/**
 * Exercises the playback-side registration pipeline as plain objects - builder assembly (per-event lambdas and
 * whole listeners composing into the bundle's single listener), builder validation, durable bundle storage in a
 * fresh {@link ClientApiBackend}, and {@link IRegistration} removal - by invoking the assembled listener's
 * callbacks directly. No AL, no session: dispatch onto the internal registries is a later task's integration
 * concern.
 */
class AudioRegistrationBuilderTest {

    private final ClientApiBackend backend = new ClientApiBackend();

    private AudioRegistrationBundle onlyBundle() {
        List<AudioRegistrationBundle> bundles = backend.audioBundlesView();
        assertEquals(1, bundles.size());
        return bundles.get(0);
    }

    @Test
    void perEventLambdasAreInvokedByTheAssembledListener() {
        List<String> events = new ArrayList<>();
        UUID id = UUID.randomUUID();
        backend.audio()
            .register("addon")
            .onContextCreated(deviceHandle -> events.add("contextCreated:" + deviceHandle))
            .onContextDestroying(() -> events.add("contextDestroying"))
            .onSourceCreated((sourceId, handle) -> events.add("sourceCreated:" + sourceId + ":" + handle))
            .onSourceDestroying((sourceId, handle) -> events.add("sourceDestroying:" + sourceId + ":" + handle))
            .onAudioTick(() -> events.add("audioTick"))
            .done();

        IAudioLifecycleListener listener = onlyBundle().listener();
        listener.contextCreated(42L);
        listener.sourceCreated(id, 7);
        listener.audioTick();
        listener.sourceDestroying(id, 7);
        listener.contextDestroying();

        assertEquals(
            Arrays.asList(
                "contextCreated:42",
                "sourceCreated:" + id + ":7",
                "audioTick",
                "sourceDestroying:" + id + ":7",
                "contextDestroying"),
            events);
    }

    @Test
    void wholeListenerAndLambdaComposeAndBothFire() {
        List<String> events = new ArrayList<>();
        IAudioLifecycleListener whole = new IAudioLifecycleListener() {

            @Override
            public void sourceCreated(UUID sourceId, int sourceHandle) {
                events.add("whole:" + sourceHandle);
            }
        };
        backend.audio()
            .register("addon")
            .lifecycle(whole)
            .onSourceCreated((sourceId, handle) -> events.add("lambda:" + handle))
            .done();

        onlyBundle().listener()
            .sourceCreated(UUID.randomUUID(), 3);

        // Registration order: the whole listener came first, so it fires first.
        assertEquals(Arrays.asList("whole:3", "lambda:3"), events);
    }

    @Test
    void repeatedCallsToTheSameMethodAllAccumulate() {
        List<String> events = new ArrayList<>();
        backend.audio()
            .register("addon")
            .onAudioTick(() -> events.add("first"))
            .onAudioTick(() -> events.add("second"))
            .done();

        onlyBundle().listener()
            .audioTick();

        assertEquals(Arrays.asList("first", "second"), events);
    }

    @Test
    void singleWholeListenerIsStoredUnwrapped() {
        IAudioLifecycleListener whole = new IAudioLifecycleListener() {};
        backend.audio()
            .register("addon")
            .lifecycle(whole)
            .done();

        assertSame(whole, onlyBundle().listener());
    }

    @Test
    void filterOnlyBundleHasNoListenerAndKeepsFilterOrder() {
        IPlaybackPcmFilter first = (sourceId, frame) -> frame;
        IPlaybackPcmFilter second = (sourceId, frame) -> frame;
        backend.audio()
            .register("addon")
            .playbackFilter(first)
            .playbackFilter(second)
            .done();

        AudioRegistrationBundle bundle = onlyBundle();
        assertNull(bundle.listener());
        assertEquals(Arrays.asList(first, second), bundle.playbackFilters());
        assertEquals("addon", bundle.addonName());
    }

    @Test
    void registerRejectsNullAndBlankAddonName() {
        assertThrows(
            NullPointerException.class,
            () -> backend.audio()
                .register(null));
        assertThrows(
            IllegalArgumentException.class,
            () -> backend.audio()
                .register("   "));
    }

    @Test
    void addonNameIsAttributionNotIdentitySoTwoBundlesMayShareIt() {
        backend.audio()
            .register("addon")
            .onAudioTick(() -> {})
            .done();
        backend.audio()
            .register("addon")
            .onAudioTick(() -> {})
            .done();

        assertEquals(2, backend.audioBundlesView().size());
    }

    @Test
    void doneOnAnEmptyBundleThrowsAndStoresNothing() {
        assertThrows(
            IllegalStateException.class,
            () -> backend.audio()
                .register("addon")
                .done());
        assertTrue(backend.audioBundlesView().isEmpty());
    }

    @Test
    void builderIsSingleUseSoASecondDoneThrows() {
        IAudioRegistrationBuilder builder = backend.audio()
            .register("addon")
            .onAudioTick(() -> {});
        builder.done();

        assertThrows(IllegalStateException.class, builder::done);
        // The failed second done() must not have double-stored the bundle.
        assertEquals(1, backend.audioBundlesView().size());
    }

    @Test
    void mutatorAfterDoneThrows() {
        IAudioRegistrationBuilder builder = backend.audio()
            .register("addon")
            .onAudioTick(() -> {});
        builder.done();

        assertThrows(IllegalStateException.class, () -> builder.onAudioTick(() -> {}));
    }

    @Test
    void builderMethodsRejectNullArguments() {
        IAudioRegistrationBuilder builder = backend.audio()
            .register("addon");

        assertThrows(NullPointerException.class, () -> builder.lifecycle(null));
        assertThrows(NullPointerException.class, () -> builder.onContextCreated(null));
        assertThrows(NullPointerException.class, () -> builder.onContextDestroying(null));
        assertThrows(NullPointerException.class, () -> builder.onSourceCreated(null));
        assertThrows(NullPointerException.class, () -> builder.onSourceDestroying(null));
        assertThrows(NullPointerException.class, () -> builder.onAudioTick(null));
        assertThrows(NullPointerException.class, () -> builder.playbackFilter(null));
    }

    @Test
    void abandonedBuilderStoresNothing() {
        backend.audio()
            .register("addon")
            .onAudioTick(() -> {});

        assertTrue(backend.audioBundlesView().isEmpty());
    }

    @Test
    void unregisterRemovesExactlyItsOwnBundleAndIsIdempotent() {
        IRegistration first = backend.audio()
            .register("first")
            .onAudioTick(() -> {})
            .done();
        backend.audio()
            .register("second")
            .onAudioTick(() -> {})
            .done();

        first.unregister();
        assertEquals(1, backend.audioBundlesView().size());
        assertEquals(
            "second",
            backend.audioBundlesView()
                .get(0)
                .addonName());

        first.unregister();
        assertEquals(1, backend.audioBundlesView().size());
    }

    @Test
    void auxiliarySendsRejectsValuesOutsideOneToEight() {
        assertThrows(
            IllegalArgumentException.class,
            () -> backend.audio()
                .register("addon")
                .auxiliarySends(0));
        assertThrows(
            IllegalArgumentException.class,
            () -> backend.audio()
                .register("addon")
                .auxiliarySends(9));
        assertThrows(
            IllegalArgumentException.class,
            () -> backend.audio()
                .register("addon")
                .auxiliarySends(-1));
    }

    @Test
    void auxiliarySendsAloneMakesTheBundleNonEmpty() {
        IRegistration registration = backend.audio()
            .register("addon")
            .auxiliarySends(4)
            .done();

        AudioRegistrationBundle bundle = onlyBundle();
        assertNull(bundle.listener());
        assertTrue(bundle.playbackFilters()
            .isEmpty());
        assertEquals(4, bundle.auxiliarySends());
        registration.unregister();
    }

    @Test
    void repeatedAuxiliarySendsKeepsTheLargestNotTheLast() {
        backend.audio()
            .register("addon")
            .auxiliarySends(4)
            .auxiliarySends(2)
            .done();

        assertEquals(4, onlyBundle().auxiliarySends());
    }

    @Test
    void effectiveAuxiliarySendsIsZeroWhenNoBundleAsksForAny() {
        backend.audio()
            .register("addon")
            .onAudioTick(() -> {})
            .done();

        assertEquals(0, backend.effectiveAuxiliarySends());
        assertEquals(0, onlyBundle().auxiliarySends());
    }

    @Test
    void effectiveAuxiliarySendsIsTheMaxAcrossLiveBundles() {
        backend.audio()
            .register("a")
            .auxiliarySends(2)
            .done();
        backend.audio()
            .register("b")
            .lifecycle(new IAudioLifecycleListener() {})
            .done();
        backend.audio()
            .register("c")
            .auxiliarySends(4)
            .done();

        assertEquals(4, backend.effectiveAuxiliarySends());
    }

    @Test
    void closingTheTopRegistrationDropsItsContributionFromTheAggregate() {
        backend.audio()
            .register("a")
            .auxiliarySends(2)
            .done();
        IRegistration top = backend.audio()
            .register("b")
            .auxiliarySends(6)
            .done();
        assertEquals(6, backend.effectiveAuxiliarySends());

        top.close();
        assertEquals(2, backend.effectiveAuxiliarySends());
    }

    @Test
    void closeDelegatesToUnregister() {
        IRegistration registration = backend.audio()
            .register("addon")
            .onAudioTick(() -> {})
            .done();

        registration.close();

        assertTrue(backend.audioBundlesView().isEmpty());
        // close() after unregister() is the idempotence contract too.
        registration.close();
    }
}
