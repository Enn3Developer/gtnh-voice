package com.enn3developer.gtnhvoice.client.api;

import java.util.List;
import java.util.function.Supplier;

import com.enn3developer.gtnhvoice.api.client.VoiceFormat;

/**
 * One live, stateful instance of a compiled {@link IPcmChain} recipe - the object that actually processes
 * frames. Built from the recorder's factory list, so its stateful stages (biquads, sample-and-hold, ...) are
 * this instance's alone: the playback path holds one per voice source, the capture path one per session, which
 * is what keeps their delay lines independent. Only ever stepped sequentially by its owning filter, so no
 * locking - which is also why the {@code double[]} working buffer can be a reused per-instance field rather
 * than a per-frame allocation.
 * <p>
 * {@link #process(short[])} converts the frame into the reused normalized {@code double} buffer, runs every
 * stage in order, and only THEN writes the results back into the caller's {@code frame} array in place and
 * returns it - the zero-allocation output the filter contract's mutate-in-place permission allows. A stage that
 * throws propagates out BEFORE the write-back, so {@code frame} is left untouched and the wrapping adapter
 * passes the original through with a throttled log, giving the chain the same failure isolation a raw filter
 * has. The reused {@code double} buffer is the safety net: the input shorts are never overwritten until the
 * whole chain has succeeded.
 */
final class PcmChainPipeline {

    private final FrameStage[] stages;
    // Reused every frame: one pipeline is stepped strictly sequentially, so the working buffer needs no
    // per-frame allocation and no locking. Sized to the fixed frame length the capture/playback paths guarantee.
    private final double[] buffer = new double[VoiceFormat.FRAME_SAMPLES];

    PcmChainPipeline(List<Supplier<FrameStage>> factories) {
        stages = new FrameStage[factories.size()];
        for (int i = 0; i < stages.length; i++) {
            stages[i] = factories.get(i)
                .get();
        }
    }

    /**
     * Runs one frame through every stage, then writes the result back into {@code frame} in place and returns
     * it. Leaves {@code frame} untouched if any stage throws (the write-back is the last step, past every
     * stage), so a throwing stage costs nothing but this frame. Returns the SAME array it was given - never a
     * reused internal buffer - so the frame stays safe to hand downstream.
     */
    short[] process(short[] frame) {
        for (int i = 0; i < frame.length; i++) {
            buffer[i] = PcmSamples.toNormalized(frame[i]);
        }
        for (FrameStage stage : stages) {
            stage.process(buffer);
        }
        for (int i = 0; i < frame.length; i++) {
            frame[i] = PcmSamples.toPcm(buffer[i]);
        }
        return frame;
    }
}
