package com.enn3developer.gtnhvoice.client.api;

import java.util.List;
import java.util.function.Supplier;

import com.enn3developer.gtnhvoice.api.client.ICapturePcmFilter;

/**
 * A compiled {@link IPcmChain} recipe as a capture filter - what {@code ICaptureRegistrationBuilder.chain(...)}
 * produces, plugged into the same seam a raw {@link ICapturePcmFilter} occupies. There is exactly one microphone
 * and capture calls are strictly sequential, so this holds ONE {@link PcmChainPipeline}, rebuilt fresh on each
 * {@link #reset()} so IIR state never carries across a disconnect/reconnect.
 * <p>
 * The bundle keeps a typed reference to this filter, and {@link ClientApiBackend#resetCaptureChains()} calls
 * {@link #reset()} directly at each new capture session - no {@code onNewCaptureSession} relay tunnelled through
 * the gate and adapter decorators. The pipeline reference is volatile: {@link #reset()} runs on the
 * session-transition thread while {@link #process(short[])} runs on the capture-send thread. Failure isolation
 * is inherited, not reimplemented - a throwing stage propagates out of {@link #process} and the wrapping capture
 * adapter passes the frame through with a throttled log.
 */
final class ChainCaptureFilter implements ICapturePcmFilter {

    private final List<Supplier<FrameStage>> factories;
    private volatile PcmChainPipeline pipeline;

    ChainCaptureFilter(List<Supplier<FrameStage>> factories) {
        this.factories = factories;
        this.pipeline = new PcmChainPipeline(factories);
    }

    @Override
    public short[] process(short[] frame) {
        return pipeline.process(frame);
    }

    /** Drops the accumulated DSP state: the next frame starts from a fresh pipeline. */
    void reset() {
        pipeline = new PcmChainPipeline(factories);
    }
}
