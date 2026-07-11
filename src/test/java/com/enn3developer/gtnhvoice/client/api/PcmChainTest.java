package com.enn3developer.gtnhvoice.client.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.client.ICapturePcmFilter;
import com.enn3developer.gtnhvoice.api.client.IPcmChain;
import com.enn3developer.gtnhvoice.api.client.IRegistration;
import com.enn3developer.gtnhvoice.api.client.VoiceFormat;

/**
 * Exercises the {@link IPcmChain} recipe stack directly (recorder -> pipeline -> compiled filter), the
 * {@link FilterGate} passthrough, and the per-source / per-session pipeline lifecycle - the parts of the two
 * additive addon-API features that carry real logic, all reachable in-package without a {@code VoiceClientManager}
 * or an AL device.
 */
class PcmChainTest {

    private static final int N = VoiceFormat.FRAME_SAMPLES;

    private static short[] frameOf(double normalized) {
        short[] frame = new short[N];
        short value = PcmSamples.toPcm(normalized);
        for (int i = 0; i < N; i++) {
            frame[i] = value;
        }
        return frame;
    }

    private static PcmChainPipeline pipeline(java.util.function.Consumer<IPcmChain> spec) {
        PcmChainRecorder recorder = new PcmChainRecorder();
        spec.accept(recorder);
        return new PcmChainPipeline(recorder.compile());
    }

    @Test
    void gainDoublesAndClamps() {
        short[] doubled = pipeline(c -> c.gain(2.0)).process(frameOf(0.25));
        assertEquals(PcmSamples.toPcm(0.5), doubled[0]);

        short[] clamped = pipeline(c -> c.gain(2.0)).process(frameOf(0.75));
        assertEquals(Short.MAX_VALUE, clamped[0], "gain overshoot must hard-clip at full scale");
    }

    @Test
    void downsampleHoldRepeatsEverySample() {
        short[] input = new short[N];
        for (int i = 0; i < N; i++) {
            input[i] = (short) (i - N / 2); // a ramp, so held vs live samples differ
        }
        short[] out = pipeline(c -> c.downsampleHold(2)).process(input);
        for (int i = 0; i + 1 < N; i += 2) {
            assertEquals(out[i], out[i + 1], "sample-and-hold must repeat each latched sample");
        }
    }

    @Test
    void softClipCompressesOverdriveBelowHardClipping() {
        // gain(2) on 0.6 overshoots to 1.2: a hard clamp pins it to full scale, tanh rounds it off well below.
        short[] hard = pipeline(c -> c.gain(2.0)).process(frameOf(0.6));
        short[] soft = pipeline(
            c -> c.gain(2.0)
                .softClip()).process(frameOf(0.6));
        assertEquals(Short.MAX_VALUE, hard[0], "without soft clip the overshoot hard-clips");
        assertTrue(soft[0] > 0 && soft[0] < hard[0], "tanh soft clip rounds the peak down below the hard ceiling");
    }

    @Test
    void mulawIsALossyRoundTripThatStaysBounded() {
        short[] in = frameOf(0.3);
        short[] out = pipeline(IPcmChain::mulaw).process(in.clone());
        assertNotEquals(in[0], out[0], "mu-law companding must perturb the sample");
        assertTrue(Math.abs(out[0] - in[0]) < 2000, "but only slightly - it is a round trip, not a mangle");
    }

    @Test
    void processWritesBackIntoTheInputArrayAndReturnsIt() {
        short[] in = frameOf(0.25);
        short[] out = pipeline(c -> c.gain(2.0)).process(in);
        assertSame(in, out, "the pipeline mutates and returns the caller's own array, never a reused buffer");
        assertEquals(PcmSamples.toPcm(0.5), out[0], "and the write-back reflects the stages");
    }

    @Test
    void frameOpSeesAndMutatesTheWholeFrame() {
        short[] out = pipeline(c -> c.frame(f -> java.util.Arrays.fill(f, (short) 0))).process(frameOf(0.5));
        assertArrayEquals(new short[N], out, "a frame op that zeroes the buffer must win");
    }

    @Test
    void highPassStripsDcButLetsAnEdgeThrough() {
        // A constant (DC) frame through a high-pass decays toward zero; a fresh pipeline starts at rest.
        short[] out = pipeline(c -> c.highPass(2000)).process(frameOf(0.5));
        assertTrue(Math.abs(out[N - 1]) < Math.abs(PcmSamples.toPcm(0.5)), "high-pass must attenuate steady DC");
    }

    @Test
    void bandPassRecordsTwoCascadedSections() {
        PcmChainRecorder recorder = new PcmChainRecorder();
        recorder.bandPass(300, 3400);
        assertEquals(
            2,
            recorder.compile()
                .size(),
            "band-pass is a high-pass cascaded into a low-pass");
    }

    @Test
    void emptySpecIsRejectedAtCompile() {
        assertThrows(IllegalArgumentException.class, () -> new PcmChainRecorder().compile());
    }

    @Test
    void argumentsAreValidatedEagerly() {
        PcmChainRecorder r = new PcmChainRecorder();
        assertThrows(IllegalArgumentException.class, () -> r.highPass(0));
        assertThrows(IllegalArgumentException.class, () -> r.lowPass(VoiceFormat.SAMPLE_RATE));
        assertThrows(IllegalArgumentException.class, () -> r.highPass(1000, 0));
        assertThrows(IllegalArgumentException.class, () -> r.bandPass(3400, 300));
        assertThrows(IllegalArgumentException.class, () -> r.gain(0));
        assertThrows(IllegalArgumentException.class, () -> r.gain(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> r.noise(1.0));
        assertThrows(IllegalArgumentException.class, () -> r.downsampleHold(0));
        assertThrows(NullPointerException.class, () -> r.map(null));
        assertThrows(NullPointerException.class, () -> r.stage(null));
        assertThrows(NullPointerException.class, () -> r.frame(null));
    }

    @Test
    void mapIsSharedStatelessAndStageIsPerInstanceStateful() {
        // stage() supplier is invoked once per pipeline instance - two pipelines must get two Stage objects.
        int[] built = { 0 };
        Supplier<IPcmChain.Stage> supplier = () -> {
            built[0]++;
            return x -> x;
        };
        PcmChainRecorder recorder = new PcmChainRecorder();
        recorder.stage(supplier);
        List<Supplier<FrameStage>> factories = recorder.compile();
        new PcmChainPipeline(factories);
        new PcmChainPipeline(factories);
        assertEquals(2, built[0], "each pipeline instance builds its own stateful stage");
    }

    @Test
    void gateOffPassesThroughUntouchedAndTogglesLive() {
        FilterGate gate = new FilterGate(false);
        ICapturePcmFilter doubler = frame -> {
            short[] out = frame.clone();
            for (int i = 0; i < out.length; i++) {
                out[i] *= 2;
            }
            return out;
        };
        GatedCaptureFilter gated = new GatedCaptureFilter(gate, doubler);

        short[] in = frameOf(0.1);
        assertSameContent(in, gated.process(in), "gated-off filter is a passthrough");

        gate.setEnabled(true);
        assertNotEquals(in[0], gated.process(in)[0], "flipping the gate on lets the filter run from the next frame");
    }

    @Test
    void captureSessionResetGivesAFreshPipeline() {
        // Feed DC into a high-pass so it accumulates state, then reset: the same input on a fresh pipeline yields
        // the identical first-frame response, proving the delay line was cleared.
        ChainCaptureFilter filter = new ChainCaptureFilter(compile(c -> c.highPass(1500)));
        short[] in = frameOf(0.5);

        short[] firstEver = filter.process(in.clone());
        filter.process(in.clone()); // dirty the state
        filter.reset();
        short[] afterReset = filter.process(in.clone());

        assertArrayEquals(firstEver, afterReset, "a new capture session must start from rest");
    }

    @Test
    void playbackKeepsIndependentPipelinesPerSourceAndEvicts() {
        ChainPlaybackFilter filter = new ChainPlaybackFilter(compile(c -> c.highPass(1500)));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        short[] in = frameOf(0.5);

        // process mutates its argument in place, so feed clones and keep the outputs to compare.
        short[] aFirst = filter.process(a, in.clone());
        filter.process(a, in.clone()); // advance A's state only
        short[] bFirst = filter.process(b, in.clone());
        assertArrayEquals(aFirst, bFirst, "each source starts from its own fresh delay line");

        // Evicting A drops its state, so A's next frame matches a first-ever frame again.
        filter.evict(a);
        assertArrayEquals(aFirst, filter.process(a, in.clone()), "eviction rebuilds the source pipeline fresh");
    }

    @Test
    void sourceCreatedEvictionRecoversFromAStaleInFlightReinsert() {
        // #4: a decode-thread frame in flight can re-insert a pipeline right after evict() drops it. The
        // cleanup listener's sourceCreated re-eviction guarantees a (re)appearing speaker still starts at rest.
        ChainPlaybackFilter filter = new ChainPlaybackFilter(compile(c -> c.highPass(1500)));
        ChainPlaybackCleanupListener cleanup = new ChainPlaybackCleanupListener(Collections.singletonList(filter));
        UUID a = UUID.randomUUID();
        short[] in = frameOf(0.5);

        short[] fresh = filter.process(UUID.randomUUID(), in.clone()); // first-frame response of a virgin pipeline

        filter.process(a, in.clone()); // A speaks
        filter.evict(a);               // A's source is destroyed
        filter.process(a, in.clone()); // in-flight frame re-inserts a stale pipeline...
        filter.process(a, in.clone()); // ...and dirties it

        cleanup.sourceCreated(a, 0);   // A reappears: drop any lingering entry
        assertArrayEquals(fresh, filter.process(a, in.clone()), "sourceCreated eviction restarts the source fresh");
    }

    @Test
    void registrationFrontsTheGateAndGoesInertAfterUnregister() {
        ClientApiBackend backend = new ClientApiBackend();
        IRegistration registration = backend.newAddonBuilder("addon")
            .register()
            .capture()
            .chain(c -> c.gain(1.5))
            .initiallyEnabled(false)
            .done();

        assertFalse(registration.isFilterEnabled(), "initiallyEnabled(false) seeds the gate off");
        registration.setFilterEnabled(true);
        assertTrue(registration.isFilterEnabled());

        registration.unregister();
        assertFalse(registration.isFilterEnabled(), "an unregistered handle reports false");
        registration.setFilterEnabled(true);
        assertFalse(registration.isFilterEnabled(), "and ignores further flips");
    }

    @Test
    void chainCountsAsAFilterForTheEmptyBundleCheck() {
        ClientApiBackend backend = new ClientApiBackend();
        IRegistration registration = backend.newAddonBuilder("addon")
            .register()
            .capture()
            .chain(c -> c.mulaw())
            .done();
        assertEquals(
            1,
            backend.captureBundlesView()
                .size());
        registration.unregister();
    }

    @Test
    void emptyChainSpecThrowsAtTheCallSite() {
        ClientApiBackend backend = new ClientApiBackend();
        assertThrows(
            IllegalArgumentException.class,
            () -> backend.newAddonBuilder("addon")
                .register()
                .capture()
                .chain(c -> {}));
    }

    private static List<Supplier<FrameStage>> compile(java.util.function.Consumer<IPcmChain> spec) {
        PcmChainRecorder recorder = new PcmChainRecorder();
        spec.accept(recorder);
        return recorder.compile();
    }

    private static void assertSameContent(short[] expected, short[] actual, String message) {
        assertArrayEquals(expected, actual, message);
    }
}
