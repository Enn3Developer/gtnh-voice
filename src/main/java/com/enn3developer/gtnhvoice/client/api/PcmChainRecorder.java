package com.enn3developer.gtnhvoice.client.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.api.client.IPcmChain;
import com.enn3developer.gtnhvoice.api.client.IPlaybackFrame;
import com.enn3developer.gtnhvoice.api.client.VoiceFormat;
import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;

/**
 * The one {@link IPcmChain} implementation - the recording end of a chain recipe. Each fluent call validates its
 * arguments EAGERLY (throwing {@link IllegalArgumentException} from the addon's spec lambda, never later on the
 * audio path) and appends one stage FACTORY: a {@link Supplier} of {@link FrameStage} the pipeline invokes once
 * per instance. Stateless stages (gain, soft clip, mulaw, {@link #map}) share a single {@link FrameStage} across
 * instances; stateful ones (biquads, noise, sample-and-hold, {@link #stage}) hand back a fresh instance each
 * time, which is what gives every voice source / capture session its own delay lines.
 * <p>
 * A recorder knows which path it records for. A playback recorder accepts the frame-aware features -
 * {@link #when} branches and stages overriding {@link Stage#beginFrame} - and its pipelines receive each frame's
 * {@link IPlaybackFrame}. A capture recorder rejects both at the call site with {@link IllegalArgumentException}:
 * the mic path has no frame context, so a frame-aware recipe there could only ever misbehave silently.
 * <p>
 * Not thread-safe and not meant to be: a recorder is a throwaway object driven by one spec lambda on one thread,
 * then {@link #compile()}d into an immutable factory list the pipelines read.
 */
final class PcmChainRecorder implements IPcmChain {

    private static final double NYQUIST = VoiceFormat.SAMPLE_RATE / 2.0;

    private static final double MULAW_MU = 255.0;
    private static final double MULAW_LOG1P_MU = Math.log1p(MULAW_MU);

    private static final long CONDITION_FAILURE_LOG_INTERVAL_MILLIS = 1_000L;

    private final List<Supplier<FrameStage>> factories = new ArrayList<>();
    private final boolean playback;
    // Attribution for the when(...) condition failure log; null on capture, which has no when(...).
    @Nullable
    private final String addonName;

    private PcmChainRecorder(boolean playback, @Nullable String addonName) {
        this.playback = playback;
        this.addonName = addonName;
    }

    /** A recorder for {@code playbackChain(...)}: frame-aware features allowed, failures attributed to the addon. */
    static PcmChainRecorder forPlayback(String addonName) {
        return new PcmChainRecorder(true, addonName);
    }

    /** A recorder for a capture {@code chain(...)}: frame-aware features are rejected at the call site. */
    static PcmChainRecorder forCapture() {
        return new PcmChainRecorder(false, null);
    }

    @Override
    public IPcmChain highPass(double hz) {
        return highPass(hz, Biquad.BUTTERWORTH_Q);
    }

    @Override
    public IPcmChain highPass(double hz, double q) {
        requireCutoff(hz, "highPass hz");
        requireQ(q);
        factories.add(() -> statefulSample(Biquad.highPass(hz, q)::step));
        return this;
    }

    @Override
    public IPcmChain lowPass(double hz) {
        return lowPass(hz, Biquad.BUTTERWORTH_Q);
    }

    @Override
    public IPcmChain lowPass(double hz, double q) {
        requireCutoff(hz, "lowPass hz");
        requireQ(q);
        factories.add(() -> statefulSample(Biquad.lowPass(hz, q)::step));
        return this;
    }

    @Override
    public IPcmChain bandPass(double lowHz, double highHz) {
        requireCutoff(lowHz, "bandPass lowHz");
        requireCutoff(highHz, "bandPass highHz");
        if (lowHz >= highHz)
            throw new IllegalArgumentException("bandPass needs lowHz < highHz, got " + lowHz + " and " + highHz);
        // A band-pass is a high-pass corner cascaded into a low-pass corner - two independent sections.
        factories.add(() -> statefulSample(Biquad.highPass(lowHz, Biquad.BUTTERWORTH_Q)::step));
        factories.add(() -> statefulSample(Biquad.lowPass(highHz, Biquad.BUTTERWORTH_Q)::step));
        return this;
    }

    @Override
    public IPcmChain gain(double factor) {
        if (!Double.isFinite(factor) || factor <= 0.0)
            throw new IllegalArgumentException("gain factor must be finite and > 0, got " + factor);
        addStateless(x -> x * factor);
        return this;
    }

    @Override
    public IPcmChain softClip() {
        addStateless(Math::tanh);
        return this;
    }

    @Override
    public IPcmChain mulaw() {
        addStateless(PcmChainRecorder::mulawRoundTrip);
        return this;
    }

    @Override
    public IPcmChain noise(double amount) {
        if (!(amount >= 0.0) || amount >= 1.0)
            throw new IllegalArgumentException("noise amount must be in [0, 1), got " + amount);
        // Fresh RNG per pipeline so speakers hiss independently and capture sessions never repeat a sequence.
        factories.add(() -> {
            Random rng = new Random();
            return frame -> {
                for (int i = 0; i < frame.length; i++) {
                    frame[i] += (rng.nextDouble() * 2.0 - 1.0) * amount;
                }
            };
        });
        return this;
    }

    @Override
    public IPcmChain downsampleHold(int factor) {
        if (factor < 1) throw new IllegalArgumentException("downsampleHold factor must be >= 1, got " + factor);
        factories.add(() -> new SampleHold(factor));
        return this;
    }

    @Override
    public IPcmChain map(DoubleUnaryOperator perSample) {
        Objects.requireNonNull(perSample, "perSample");
        addStateless(perSample);
        return this;
    }

    @Override
    public IPcmChain stage(Supplier<Stage> perSourceState) {
        Objects.requireNonNull(perSourceState, "perSourceState");
        if (!playback) rejectFrameAwareStage(perSourceState);
        factories.add(() -> new CustomStage(Objects.requireNonNull(perSourceState.get(), "perSourceState.get()")));
        return this;
    }

    @Override
    public IPcmChain frame(Consumer<short[]> perFrame) {
        Objects.requireNonNull(perFrame, "perFrame");
        // One FrameOp per pipeline instance (the supplier runs once per pipeline), each owning a reusable PCM
        // scratch array - the frame op materializes the chain to 16-bit PCM at its position, so overshoot is
        // clamped here. The perFrame consumer itself is shared across every pipeline and may run concurrently
        // across sources, so it must be stateless (see the interface contract).
        factories.add(() -> new FrameOp(perFrame));
        return this;
    }

    @Override
    public IPcmChain when(Predicate<IPlaybackFrame> condition, Consumer<IPcmChain> branch) {
        if (!playback) throw new IllegalArgumentException(
            "when(...) is playback-only: captured mic frames carry no IPlaybackFrame to test");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(branch, "branch");
        PcmChainRecorder recorder = forPlayback(addonName);
        branch.accept(recorder);
        if (recorder.factories.isEmpty())
            throw new IllegalArgumentException("when(...) branch recorded no stages: add at least one stage");
        List<Supplier<FrameStage>> branchFactories = new ArrayList<>(recorder.factories);
        // One throttle per recorded branch, shared by every source's instance: a condition that throws for every
        // speaker logs once per interval, not once per speaker.
        AtomicLong lastFailureLogMillis = new AtomicLong();
        factories.add(() -> new Branch(condition, branchFactories, addonName, lastFailureLogMillis));
        return this;
    }

    /**
     * Freezes the recorded recipe into the immutable factory list the pipelines instantiate from.
     *
     * @throws IllegalArgumentException if the spec recorded no stages - a caller bug caught at the call site
     */
    List<Supplier<FrameStage>> compile() {
        if (factories.isEmpty())
            throw new IllegalArgumentException("chain spec recorded no stages: add at least one stage");
        return new ArrayList<>(factories);
    }

    /** A stateless per-sample op is one shared {@link FrameStage}, reused across every pipeline instance. */
    private void addStateless(DoubleUnaryOperator op) {
        FrameStage shared = perSample(op);
        factories.add(() -> shared);
    }

    private static FrameStage perSample(DoubleUnaryOperator op) {
        return frame -> {
            for (int i = 0; i < frame.length; i++) {
                frame[i] = op.applyAsDouble(frame[i]);
            }
        };
    }

    /** A stateful per-sample op: the {@code op} closes over one pipeline's mutable state, so it is NOT shared. */
    private static FrameStage statefulSample(DoubleUnaryOperator op) {
        return perSample(op);
    }

    /**
     * The capture-side check behind {@link #stage}: builds one probe stage and refuses it when it overrides the
     * playback-only {@link Stage#beginFrame} - such a stage was written to read frame context the mic path can
     * never give it.
     */
    private static void rejectFrameAwareStage(Supplier<Stage> perSourceState) {
        Stage probe = Objects.requireNonNull(perSourceState.get(), "perSourceState.get()");
        if (overridesBeginFrame(probe)) throw new IllegalArgumentException(
            "stage " + probe.getClass()
                .getName()
                + " overrides Stage.beginFrame, which only playback chains call: a capture chain has no frame context");
    }

    private static boolean overridesBeginFrame(Stage stage) {
        try {
            return stage.getClass()
                .getMethod("beginFrame", IPlaybackFrame.class)
                .getDeclaringClass() != Stage.class;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Stage.beginFrame is part of the API, every Stage has it", e);
        }
    }

    private static void requireCutoff(double hz, String name) {
        if (!(hz > 0.0) || hz >= NYQUIST)
            throw new IllegalArgumentException(name + " must be in (0, " + NYQUIST + "), got " + hz);
    }

    private static void requireQ(double q) {
        if (!(q > 0.0)) throw new IllegalArgumentException("q must be > 0, got " + q);
    }

    private static double mulawRoundTrip(double x) {
        double sign = Math.signum(x);
        double magnitude = Math.min(1.0, Math.abs(x));
        // Compress to the mu-law domain, quantize to 8 bits (255 steps) - the step that stamps on the telephone
        // grit - then expand straight back.
        double compressed = sign * Math.log1p(MULAW_MU * magnitude) / MULAW_LOG1P_MU;
        double quantized = Math.round(compressed * 127.0) / 127.0;
        double expandMagnitude = (Math.pow(1.0 + MULAW_MU, Math.abs(quantized)) - 1.0) / MULAW_MU;
        return Math.signum(quantized) * expandMagnitude;
    }

    /**
     * A frame-level op stage: converts the normalized buffer to a reused 16-bit PCM scratch array, hands it to
     * the addon consumer to mutate in place, and splices the result back. The scratch is per pipeline instance
     * (reused every frame, allocation-free); the {@code perFrame} consumer is shared across pipelines, so it must
     * be stateless.
     */
    private static final class FrameOp implements FrameStage {

        private final Consumer<short[]> perFrame;
        private final short[] pcm = new short[VoiceFormat.FRAME_SAMPLES];

        FrameOp(Consumer<short[]> perFrame) {
            this.perFrame = perFrame;
        }

        @Override
        public void process(double[] frame) {
            for (int i = 0; i < frame.length; i++) {
                pcm[i] = PcmSamples.toPcm(frame[i]);
            }
            perFrame.accept(pcm);
            for (int i = 0; i < frame.length; i++) {
                frame[i] = PcmSamples.toNormalized(pcm[i]);
            }
        }
    }

    /**
     * A {@link #stage} instance: hands each playback frame's context to {@link Stage#beginFrame}, then steps every
     * sample through the addon's stage. Capture pipelines pass no context, so {@code beginFrame} never fires there.
     */
    private static final class CustomStage implements FrameStage {

        private final Stage stage;

        CustomStage(Stage stage) {
            this.stage = stage;
        }

        @Override
        public void process(double[] frame) {
            for (int i = 0; i < frame.length; i++) {
                frame[i] = stage.step(frame[i]);
            }
        }

        @Override
        public void process(@Nullable IPlaybackFrame context, double[] frame) {
            if (context != null) stage.beginFrame(context);
            process(frame);
        }
    }

    /**
     * A {@link #when} branch - one instance per pipeline, so per voice source like every stateful stage. Runs its
     * own stages on frames whose context matches the condition and leaves every other frame untouched. Re-entry
     * after a skipped frame rebuilds those stages from the recipe, so a branch never resumes from state that went
     * stale while it was off; the stages built with the pipeline serve the first match. A throwing condition counts
     * as a non-match, logged through the throttle every instance of the recorded branch shares.
     */
    private static final class Branch implements FrameStage {

        private final Predicate<IPlaybackFrame> condition;
        private final List<Supplier<FrameStage>> factories;
        private final String addonName;
        private final AtomicLong lastFailureLogMillis;
        private FrameStage[] stages;
        // Did the previous frame run through this branch? Is the current stage set used (so stale on re-entry)?
        private boolean ranLastFrame;
        private boolean used;

        Branch(Predicate<IPlaybackFrame> condition, List<Supplier<FrameStage>> factories, String addonName,
            AtomicLong lastFailureLogMillis) {
            this.condition = condition;
            this.factories = factories;
            this.addonName = addonName;
            this.lastFailureLogMillis = lastFailureLogMillis;
            this.stages = PcmChainPipeline.instantiate(factories);
        }

        @Override
        public void process(double[] frame) {
            // No context, nothing to match - only reachable off the playback path, where when(...) is rejected.
            process(null, frame);
        }

        @Override
        public void process(@Nullable IPlaybackFrame context, double[] frame) {
            if (!matches(context)) {
                ranLastFrame = false;
                return;
            }
            if (used && !ranLastFrame) stages = PcmChainPipeline.instantiate(factories);
            ranLastFrame = true;
            used = true;
            for (FrameStage stage : stages) {
                stage.process(context, frame);
            }
        }

        private boolean matches(@Nullable IPlaybackFrame context) {
            if (context == null) return false;
            try {
                return condition.test(context);
            } catch (Throwable t) {
                if (LogThrottle.shouldLog(lastFailureLogMillis, CONDITION_FAILURE_LOG_INTERVAL_MILLIS)) {
                    GtnhVoice.LOG.error(
                        "[Playback] Addon '{}' chain when(...) condition threw; branch skipped for this frame",
                        addonName,
                        t);
                }
                return false;
            }
        }
    }

    /**
     * Sample-and-hold decimation: latches a new sample every {@code factor}th step and repeats it in between, so
     * the effective rate is {@code SAMPLE_RATE / factor}. The counter and held value span frame boundaries, so
     * this is one instance per pipeline.
     */
    private static final class SampleHold implements FrameStage {

        private final int factor;
        private int counter;
        private double held;

        SampleHold(int factor) {
            this.factor = factor;
        }

        @Override
        public void process(double[] frame) {
            for (int i = 0; i < frame.length; i++) {
                if (counter == 0) held = frame[i];
                frame[i] = held;
                counter++;
                if (counter == factor) counter = 0;
            }
        }
    }
}
