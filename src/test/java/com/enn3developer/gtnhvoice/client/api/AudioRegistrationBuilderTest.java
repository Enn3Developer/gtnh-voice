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
import com.enn3developer.gtnhvoice.api.client.IPlaybackFrame;
import com.enn3developer.gtnhvoice.api.client.IPlaybackFrameFilter;
import com.enn3developer.gtnhvoice.api.client.IPlaybackPcmFilter;
import com.enn3developer.gtnhvoice.api.client.IRegistration;
import com.enn3developer.gtnhvoice.api.client.VoiceFormat;
import com.enn3developer.gtnhvoice.client.playback.PlaybackFrame;

/**
 * Exercises the playback-side registration pipeline as plain objects - builder assembly (per-event lambdas and
 * whole listeners composing into the bundle's single listener), builder validation, durable bundle storage in a
 * fresh {@link ClientApiBackend}, and {@link IRegistration} removal - by invoking the assembled listener's
 * callbacks directly. No AL, no session: dispatch onto the internal registries is a later task's integration
 * concern.
 */
class AudioRegistrationBuilderTest {

    private final ClientApiBackend backend = new ClientApiBackend();
    private final TestAddons addons = new TestAddons(backend);

    private AudioRegistrationBundle onlyBundle() {
        List<AudioRegistrationBundle> bundles = backend.audioBundlesView();
        assertEquals(1, bundles.size());
        return bundles.get(0);
    }

    private static IPlaybackFrame frameFrom(UUID sourceId) {
        return new PlaybackFrame(sourceId, "radio", "Radio", false);
    }

    /** Feeds one silent frame through every filter of {@code bundle} in stored order, like the playback chain. */
    private static short[] runPlaybackFilters(AudioRegistrationBundle bundle, IPlaybackFrame frame) {
        short[] pcm = new short[VoiceFormat.FRAME_SAMPLES];
        for (IPlaybackFrameFilter filter : bundle.playbackFilters()) {
            pcm = filter.process(frame, pcm);
        }
        return pcm;
    }

    @Test
    void perEventLambdasAreInvokedByTheAssembledListener() {
        List<String> events = new ArrayList<>();
        UUID id = UUID.randomUUID();
        addons.audio("addon")
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
        addons.audio("addon")
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
        addons.audio("addon")
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
        addons.audio("addon")
            .lifecycle(whole)
            .done();

        assertSame(whole, onlyBundle().listener());
    }

    @Test
    void filterOnlyBundleHasNoListenerAndKeepsFilterOrder() {
        List<String> calls = new ArrayList<>();
        IPlaybackPcmFilter first = (sourceId, frame) -> {
            calls.add("first");
            return frame;
        };
        IPlaybackPcmFilter second = (sourceId, frame) -> {
            calls.add("second");
            return frame;
        };
        addons.audio("addon")
            .playbackFilter(first)
            .playbackFilter(second)
            .done();

        AudioRegistrationBundle bundle = onlyBundle();
        runPlaybackFilters(bundle, frameFrom(UUID.randomUUID()));
        assertNull(bundle.listener());
        assertEquals(Arrays.asList("first", "second"), calls);
        assertEquals("addon", bundle.addonName());
    }

    @Test
    void addonRegistrationRejectsNullAndBlankName() {
        assertThrows(NullPointerException.class, () -> backend.newAddonBuilder(null));
        assertThrows(IllegalArgumentException.class, () -> backend.newAddonBuilder("   "));
    }

    @Test
    void addonNameIsAttributionNotIdentitySoTwoBundlesMayShareIt() {
        addons.audio("addon")
            .onAudioTick(() -> {})
            .done();
        addons.audio("addon")
            .onAudioTick(() -> {})
            .done();

        assertEquals(2, backend.audioBundlesView().size());
    }

    @Test
    void doneOnAnEmptyBundleThrowsAndStoresNothing() {
        assertThrows(
            IllegalStateException.class,
            () -> addons.audio("addon")
                .done());
        assertTrue(backend.audioBundlesView().isEmpty());
    }

    @Test
    void builderIsSingleUseSoASecondDoneThrows() {
        IAudioRegistrationBuilder builder = addons.audio("addon")
            .onAudioTick(() -> {});
        builder.done();

        assertThrows(IllegalStateException.class, builder::done);
        // The failed second done() must not have double-stored the bundle.
        assertEquals(1, backend.audioBundlesView().size());
    }

    @Test
    void mutatorAfterDoneThrows() {
        IAudioRegistrationBuilder builder = addons.audio("addon")
            .onAudioTick(() -> {});
        builder.done();

        assertThrows(IllegalStateException.class, () -> builder.onAudioTick(() -> {}));
    }

    @Test
    void builderMethodsRejectNullArguments() {
        IAudioRegistrationBuilder builder = addons.audio("addon");

        assertThrows(NullPointerException.class, () -> builder.lifecycle(null));
        assertThrows(NullPointerException.class, () -> builder.onContextCreated(null));
        assertThrows(NullPointerException.class, () -> builder.onContextDestroying(null));
        assertThrows(NullPointerException.class, () -> builder.onSourceCreated(null));
        assertThrows(NullPointerException.class, () -> builder.onSourceDestroying(null));
        assertThrows(NullPointerException.class, () -> builder.onAudioTick(null));
        assertThrows(NullPointerException.class, () -> builder.playbackFilter(null));
        assertThrows(NullPointerException.class, () -> builder.playbackFrameFilter(null));
    }

    @Test
    void frameFilterSeesTheContextOfTheFrameItProcesses() {
        List<IPlaybackFrame> seen = new ArrayList<>();
        addons.audio("addon")
            .playbackFrameFilter((frame, pcm) -> {
                seen.add(frame);
                return pcm;
            })
            .done();
        IPlaybackFrame radioFrame = frameFrom(UUID.randomUUID());

        runPlaybackFilters(onlyBundle(), radioFrame);

        assertEquals(List.of(radioFrame), seen);
    }

    @Test
    void rawFilterStillGetsTheSpeakerIdAndTheSamplesAndItsOutputFlowsOn() {
        UUID speaker = UUID.randomUUID();
        List<UUID> seenIds = new ArrayList<>();
        short[] replacement = new short[VoiceFormat.FRAME_SAMPLES];
        replacement[0] = 42;
        addons.audio("addon")
            .playbackFilter((sourceId, frame) -> {
                seenIds.add(sourceId);
                return replacement;
            })
            .done();

        short[] out = runPlaybackFilters(onlyBundle(), frameFrom(speaker));

        assertEquals(List.of(speaker), seenIds, "a frame-blind filter is called with the frame's source id");
        assertSame(replacement, out, "its output is what the chain carries on");
    }

    @Test
    void rawAndFrameFiltersRunInCallOrderAcrossKinds() {
        List<String> calls = new ArrayList<>();
        addons.audio("addon")
            .playbackFilter((sourceId, frame) -> {
                calls.add("raw-1");
                return frame;
            })
            .playbackFrameFilter((frame, pcm) -> {
                calls.add("frame");
                return pcm;
            })
            .playbackFilter((sourceId, frame) -> {
                calls.add("raw-2");
                return frame;
            })
            .done();

        runPlaybackFilters(onlyBundle(), frameFrom(UUID.randomUUID()));

        assertEquals(List.of("raw-1", "frame", "raw-2"), calls);
    }

    @Test
    void frameFilterAloneMakesTheBundleNonEmpty() {
        addons.audio("addon")
            .playbackFrameFilter((frame, pcm) -> pcm)
            .done();

        assertEquals(1, onlyBundle().playbackFilters().size());
    }

    @Test
    void abandonedBuilderStoresNothing() {
        addons.audio("addon")
            .onAudioTick(() -> {});

        assertTrue(backend.audioBundlesView().isEmpty());
    }

    @Test
    void unregisterRemovesExactlyItsOwnBundleAndIsIdempotent() {
        IRegistration first = addons.audio("first")
            .onAudioTick(() -> {})
            .done();
        addons.audio("second")
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
            () -> addons.audio("addon")
                .auxiliarySends(0));
        assertThrows(
            IllegalArgumentException.class,
            () -> addons.audio("addon")
                .auxiliarySends(9));
        assertThrows(
            IllegalArgumentException.class,
            () -> addons.audio("addon")
                .auxiliarySends(-1));
    }

    @Test
    void auxiliarySendsAloneMakesTheBundleNonEmpty() {
        IRegistration registration = addons.audio("addon")
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
        addons.audio("addon")
            .auxiliarySends(4)
            .auxiliarySends(2)
            .done();

        assertEquals(4, onlyBundle().auxiliarySends());
    }

    @Test
    void effectiveAuxiliarySendsIsZeroWhenNoBundleAsksForAny() {
        addons.audio("addon")
            .onAudioTick(() -> {})
            .done();

        assertEquals(0, backend.effectiveAuxiliarySends());
        assertEquals(0, onlyBundle().auxiliarySends());
    }

    @Test
    void effectiveAuxiliarySendsIsTheMaxAcrossLiveBundles() {
        addons.audio("a")
            .auxiliarySends(2)
            .done();
        addons.audio("b")
            .lifecycle(new IAudioLifecycleListener() {})
            .done();
        addons.audio("c")
            .auxiliarySends(4)
            .done();

        assertEquals(4, backend.effectiveAuxiliarySends());
    }

    @Test
    void closingTheTopRegistrationDropsItsContributionFromTheAggregate() {
        addons.audio("a")
            .auxiliarySends(2)
            .done();
        IRegistration top = addons.audio("b")
            .auxiliarySends(6)
            .done();
        assertEquals(6, backend.effectiveAuxiliarySends());

        top.close();
        assertEquals(2, backend.effectiveAuxiliarySends());
    }

    @Test
    void closeDelegatesToUnregister() {
        IRegistration registration = addons.audio("addon")
            .onAudioTick(() -> {})
            .done();

        registration.close();

        assertTrue(backend.audioBundlesView().isEmpty());
        // close() after unregister() is the idempotence contract too.
        registration.close();
    }
}
