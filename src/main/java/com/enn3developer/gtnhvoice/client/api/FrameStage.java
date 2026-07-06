package com.enn3developer.gtnhvoice.client.api;

/**
 * One compiled stage of a {@link PcmChainPipeline}: mutates the pipeline's normalized {@code double} working
 * buffer in place. A stateful stage (biquad, sample-and-hold, ...) is a fresh instance per pipeline and is only
 * ever stepped sequentially, so it needs no synchronization for its own fields; a stateless one may be shared
 * across pipelines. The recorder ({@link PcmChainRecorder}) turns each {@link IPcmChain} call into a factory that
 * produces one of these per pipeline instance.
 */
@FunctionalInterface
interface FrameStage {

    /**
     * Processes one frame's worth of normalized samples in place. Throwing is legal - the pipeline lets it
     * propagate so the wrapping adapter's failure isolation passes the untouched frame through with a throttled
     * log, exactly as for a raw filter.
     *
     * @param frame the working buffer, {@code VoiceFormat.FRAME_SAMPLES} samples in {@code [-1, 1]}
     */
    void process(double[] frame);
}
