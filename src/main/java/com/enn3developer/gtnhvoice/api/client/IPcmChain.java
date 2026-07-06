package com.enn3developer.gtnhvoice.api.client;

import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Supplier;

import org.jetbrains.annotations.NotNull;

/**
 * Fluent DSP recipe builder handed to the spec lambda of {@link ICaptureRegistrationBuilder#chain} and
 * {@link IAudioRegistrationBuilder#playbackChain} - a compact way to assemble a per-frame filter out of stock
 * stages (biquads, gain, soft clip, telephone companding, ...) plus escape hatches for custom code, without
 * writing an {@link ICapturePcmFilter}/{@link IPlaybackPcmFilter} by hand. Addons CALL these methods; they never
 * implement this interface (the mod does), which is what keeps adding stages here source- and binary-compatible.
 * <p>
 * The spec lambda is a RECIPE, not a hot path: the mod invokes it exactly ONCE, at registration time, to record
 * the ordered list of stages. From that recipe it instantiates a FRESH stateful pipeline per pipeline instance -
 * one per voice source on the playback path, one per capture session on the capture path - so stateful stages
 * (biquads, sample-and-hold, ...) get their own independent delay lines by construction. On playback that
 * satisfies {@link IPlaybackPcmFilter}'s concurrency contract (concurrent across sources, sequential per source)
 * automatically; on capture it means IIR state never leaks across a disconnect/reconnect.
 * <p>
 * Every stage runs in the normalized {@code double} domain {@code [-1, 1]}: the mod converts the {@code short[]}
 * frame to doubles at the pipeline's input and back (with clamping) at its output, so stages never see raw PCM
 * shorts. Stages run in the order they are recorded, each feeding the next. Argument validation is EAGER - every
 * method below throws {@link IllegalArgumentException} from the spec lambda itself (registration time), never
 * later on the audio path.
 * <p>
 * The compiled chain sits at the exact same seam a hand-written raw filter occupies and inherits the identical
 * failure-isolation contract: a stage that throws skips the WHOLE chain for that one frame - the frame passes
 * through unchanged - with the same throttled, addon-attributed error log. One broken stage can neither mute
 * voice nor kill the mic. Every recording method returns {@code this} for chaining.
 */
public interface IPcmChain {

    /** Records a 2nd-order RBJ high-pass at {@code hz} with a Butterworth Q ({@code 1/sqrt(2)}). */
    IPcmChain highPass(double hz);

    /**
     * Records a 2nd-order RBJ high-pass at {@code hz} with resonance {@code q}.
     *
     * @param hz cutoff frequency, in {@code (0, VoiceFormat.SAMPLE_RATE/2)}Hz
     * @param q  filter Q, {@code > 0} (higher resonates more around the cutoff)
     * @throws IllegalArgumentException if {@code hz} is outside the open Nyquist band or {@code q <= 0}
     */
    IPcmChain highPass(double hz, double q);

    /** Records a 2nd-order RBJ low-pass at {@code hz} with a Butterworth Q ({@code 1/sqrt(2)}). */
    IPcmChain lowPass(double hz);

    /**
     * Records a 2nd-order RBJ low-pass at {@code hz} with resonance {@code q}.
     *
     * @param hz cutoff frequency, in {@code (0, VoiceFormat.SAMPLE_RATE/2)}Hz
     * @param q  filter Q, {@code > 0} (higher resonates more around the cutoff)
     * @throws IllegalArgumentException if {@code hz} is outside the open Nyquist band or {@code q <= 0}
     */
    IPcmChain lowPass(double hz, double q);

    /**
     * Records a band-pass as a high-pass at {@code lowHz} cascaded into a low-pass at {@code highHz}, both at a
     * Butterworth Q - the telephone-band shaping most voice effects want.
     *
     * @param lowHz  lower cutoff (the high-pass corner), in {@code (0, VoiceFormat.SAMPLE_RATE/2)}Hz
     * @param highHz upper cutoff (the low-pass corner), in {@code (0, VoiceFormat.SAMPLE_RATE/2)}Hz
     * @throws IllegalArgumentException if either cutoff is outside the open Nyquist band, or
     *                                  {@code lowHz >= highHz}
     */
    IPcmChain bandPass(double lowHz, double highHz);

    /**
     * Records a linear gain: every sample is multiplied by {@code factor}. Overshoot past full scale survives
     * through later normalized-domain stages and is clamped only where the chain converts back to PCM - the
     * pipeline boundary, or an earlier {@link #frame} stage if one sits between here and it - so a large factor
     * hard-clips at that point; precede it with {@link #softClip()} for a gentler ceiling.
     *
     * @param factor the linear multiplier, finite and {@code > 0}
     * @throws IllegalArgumentException if {@code factor} is not finite or is {@code <= 0}
     */
    IPcmChain gain(double factor);

    /**
     * Records a {@code tanh}-style soft clipper: a smooth saturating ceiling that rounds peaks instead of squaring
     * them.
     */
    IPcmChain softClip();

    /**
     * Records a G.711 mu-law round trip: each sample is mu-law encoded to 8 bits and decoded straight back. It
     * changes nothing about the frame's shape (still {@code short[]} PCM) but stamps it with telephone-grade
     * companding quantization - the classic low-fidelity "phone call" character.
     */
    IPcmChain mulaw();

    /**
     * Records additive white noise - a constant line-hiss bed, useful under {@link #mulaw()}/{@link #bandPass}
     * for a radio or bad-connection feel. Each pipeline instance gets its own RNG, so speakers hiss
     * independently.
     *
     * @param amount noise amplitude as a fraction of full scale, in {@code [0, 1)} ({@code 0} adds nothing)
     * @throws IllegalArgumentException if {@code amount} is outside {@code [0, 1)}
     */
    IPcmChain noise(double amount);

    /**
     * Records a sample-and-hold rate reduction: the effective sample rate drops to
     * {@code VoiceFormat.SAMPLE_RATE / factor}, each held sample repeated {@code factor} times, for a crunchy
     * bit-crushed/low-rate texture. The hold counter is per pipeline instance and spans frame boundaries.
     *
     * @param factor the decimation factor, {@code >= 1} ({@code 1} is a passthrough)
     * @throws IllegalArgumentException if {@code factor < 1}
     */
    IPcmChain downsampleHold(int factor);

    /**
     * Records a stateless custom per-sample operation. Because it holds no state, ONE {@code perSample} instance
     * is shared across every pipeline the recipe builds and may therefore run CONCURRENTLY across sources on the
     * playback path - it MUST be stateless (no fields mutated, no shared buffers). For anything that needs
     * per-source state, use {@link #stage(Supplier)} instead.
     *
     * @param perSample the sample transform, applied to each normalized sample in {@code [-1, 1]}; non-null
     * @throws NullPointerException if {@code perSample} is null
     */
    IPcmChain map(@NotNull DoubleUnaryOperator perSample);

    /**
     * Records a stateful custom per-sample stage. The {@code perSourceState} supplier is invoked ONCE PER
     * pipeline instance - once per voice source on playback, once per capture session on capture - so each
     * {@link Stage} it hands back owns its state exclusively and is only ever called sequentially. That is how a
     * hand-rolled biquad or envelope follower gets independent state per speaker for free.
     *
     * @param perSourceState supplies a fresh {@link Stage} per pipeline instance; non-null, and must not return
     *                       null
     * @throws NullPointerException if {@code perSourceState} is null
     */
    IPcmChain stage(@NotNull Supplier<Stage> perSourceState);

    /**
     * Records a frame-level operation on the raw {@code short[]} frame - the escape hatch for effects that work
     * on whole frames rather than single samples (packet dropouts, stutters, frame repeats). The consumer
     * receives a {@link VoiceFormat#FRAME_SAMPLES}-length scratch array to mutate in place, spliced back into the
     * chain's normalized buffer afterwards; it runs at this stage's position in registration order like any
     * other.
     * <p>
     * The frame op MATERIALIZES the chain to 16-bit PCM at its position: the conversion to {@code short[]} clamps
     * any accumulated overshoot to full scale HERE, unlike {@link #gain}/{@link #stage} whose overshoot survives
     * to the pipeline boundary. So place a frame op BEFORE gain stages that rely on headroom, or after the final
     * level stage - not between a gain and the {@link #softClip} meant to tame it, which would destroy the
     * headroom the soft clip needs.
     * <p>
     * Like {@link #map}, the {@code perFrame} consumer MUST be stateless: ONE instance is shared across every
     * pipeline the recipe builds and may therefore run CONCURRENTLY across sources on the playback path (no
     * fields mutated, no shared buffers). Anything needing cross-frame or per-source state belongs in
     * {@link #stage(Supplier)}, whose supplier is invoked once per pipeline instance.
     *
     * @param perFrame the stateless frame mutator; non-null
     * @throws NullPointerException if {@code perFrame} is null
     */
    IPcmChain frame(@NotNull Consumer<short[]> perFrame);

    /**
     * A stateful single-sample transform supplied to {@link IPcmChain#stage(Supplier)}. One instance belongs to
     * exactly one pipeline and is only ever stepped sequentially, so it needs no synchronization for its own
     * state.
     */
    @FunctionalInterface
    interface Stage {

        /**
         * Transforms one normalized sample.
         *
         * @param x the input sample, in {@code [-1, 1]}
         * @return the output sample (clamped to the PCM range at the pipeline boundary, so overshoot is allowed)
         */
        double step(double x);
    }
}
