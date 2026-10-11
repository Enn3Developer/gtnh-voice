package com.enn3developer.gtnhvoice.api.client;

import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Predicate;
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
 * <p>
 * Playback chains also know each frame's {@link IPlaybackFrame} - speaker, routing group, positional flag - and
 * offer two frame-aware features on top: {@link #when} branches that run only on matching frames, and
 * {@link Stage#beginFrame} for custom stages. Captured mic audio has no routing, so a capture chain rejects both
 * with {@link IllegalArgumentException} at the call site rather than silently ignoring them.
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
     * hand-rolled biquad or envelope follower gets independent state per speaker for free. (A stage inside a
     * {@link #when} branch is additionally rebuilt each time its branch is re-entered.)
     * <p>
     * On a capture chain the supplier is also invoked once right here, to check the stage it builds: a stage
     * that overrides the playback-only {@link Stage#beginFrame} is rejected.
     *
     * @param perSourceState supplies a fresh {@link Stage} per pipeline instance; non-null, and must not return
     *                       null
     * @throws NullPointerException     if {@code perSourceState} is null, or returns null on a capture chain
     * @throws IllegalArgumentException on a capture chain, if the supplied stage overrides {@link Stage#beginFrame}
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
     * PLAYBACK ONLY. Records a conditional sub-chain: every frame, {@code condition} is tested against the frame's
     * {@link IPlaybackFrame} and the stages {@code branch} records run on that frame only when it matches - a
     * frame that doesn't match passes this position untouched. The branch sits at its position like any stage, so
     * later stages see its output (or the untouched frame). Each {@code when} is tested on its own; mutually
     * exclusive conditions make a switch, e.g. radio quality by tier:
     *
     * <pre>{@code
     * addon.audio()
     *     .playbackChain(c -> c
     *         .when(f -> tierOf(f) == Tier.LOW, low -> low.bandPass(300, 3400).mulaw().downsampleHold(3).noise(0.04))
     *         .when(f -> tierOf(f) == Tier.MEDIUM, med -> med.bandPass(200, 5000).noise(0.01)))
     *     .done();
     * }</pre>
     *
     * State: the branch's stages are instantiated per voice source like the rest of the chain, so every speaker
     * gets its own delay lines. A branch that skips a frame is RESET on re-entry: the next matching frame rebuilds
     * its stages from the recipe (stage suppliers run again), so a speaker hopping between tiers restarts each
     * tier's filters from rest instead of resuming from delay-line state that went stale while the branch was off.
     * <p>
     * Threading: {@code condition} runs on the decode thread, once per frame for every branch the frame reaches,
     * before the branch's stages; it may run concurrently for different sources, so keep it cheap, stateless and
     * side-effect free. Failure isolation: a {@code condition} that throws counts as a non-match - its branch is
     * skipped for that frame (and reset when it next matches) with a throttled, addon-attributed error log, while
     * the rest of the chain and playback carry on. A stage inside the branch that throws behaves like any throwing
     * stage: the whole chain is skipped for that frame.
     * <p>
     * The {@code branch} spec is invoked IMMEDIATELY, like the chain's own spec, and branches may nest.
     *
     * @param condition picks the frames the branch runs on; non-null
     * @param branch    records the branch's stages onto the supplied {@link IPcmChain}; non-null
     * @throws IllegalArgumentException on a capture chain (whatever the arguments), or if {@code branch} records
     *                                  no stages or invalid ones
     * @throws NullPointerException     on a playback chain, if either argument is null
     */
    IPcmChain when(@NotNull Predicate<IPlaybackFrame> condition, @NotNull Consumer<IPcmChain> branch);

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

        /**
         * PLAYBACK ONLY. Called once per frame, before this stage's first {@link #step} of that frame, with the
         * frame's {@link IPlaybackFrame} - on the decode thread, sequentially with this instance's {@code step}
         * calls (the instance belongs to one voice source), so per-frame parameters can live in plain fields:
         *
         * <pre>{@code
         * c.stage(() -> new IPcmChain.Stage() {
         *
         *     private double gain = 1.0;
         *
         *     public void beginFrame(IPlaybackFrame frame) {
         *         gain = frame.positional() ? 1.0 : 0.7;
         *     }
         *
         *     public double step(double x) {
         *         return x * gain;
         *     }
         * })
         * }</pre>
         *
         * Inside a {@link IPcmChain#when} branch, only the frames that branch processes reach it. Throwing here is
         * like throwing from {@link #step}: the whole chain is skipped for that frame. Capture chains never call
         * it, and their {@link IPcmChain#stage} rejects a stage that overrides it. The default does nothing.
         *
         * @param frame the context of the frame about to be stepped
         */
        default void beginFrame(@NotNull IPlaybackFrame frame) {}
    }
}
