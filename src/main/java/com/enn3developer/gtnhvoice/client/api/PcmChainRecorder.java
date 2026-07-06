package com.enn3developer.gtnhvoice.client.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Supplier;

import com.enn3developer.gtnhvoice.api.client.IPcmChain;
import com.enn3developer.gtnhvoice.api.client.VoiceFormat;

/**
 * The one {@link IPcmChain} implementation - the recording end of a chain recipe. Each fluent call validates its
 * arguments EAGERLY (throwing {@link IllegalArgumentException} from the addon's spec lambda, never later on the
 * audio path) and appends one stage FACTORY: a {@link Supplier} of {@link FrameStage} the pipeline invokes once
 * per instance. Stateless stages (gain, soft clip, mulaw, {@link #map}) share a single {@link FrameStage} across
 * instances; stateful ones (biquads, noise, sample-and-hold, {@link #stage}) hand back a fresh instance each
 * time, which is what gives every voice source / capture session its own delay lines.
 * <p>
 * Not thread-safe and not meant to be: a recorder is a throwaway object driven by one spec lambda on one thread,
 * then {@link #compile()}d into an immutable factory list the pipelines read.
 */
final class PcmChainRecorder implements IPcmChain {

    private static final double NYQUIST = VoiceFormat.SAMPLE_RATE / 2.0;

    private static final double MULAW_MU = 255.0;
    private static final double MULAW_LOG1P_MU = Math.log1p(MULAW_MU);

    private final List<Supplier<FrameStage>> factories = new ArrayList<>();

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
        factories.add(() -> {
            Stage stage = Objects.requireNonNull(perSourceState.get(), "perSourceState.get()");
            return statefulSample(stage::step);
        });
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
