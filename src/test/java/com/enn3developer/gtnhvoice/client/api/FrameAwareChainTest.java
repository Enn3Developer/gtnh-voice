package com.enn3developer.gtnhvoice.client.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.client.IPcmChain;
import com.enn3developer.gtnhvoice.api.client.IPlaybackFrame;
import com.enn3developer.gtnhvoice.api.client.IVoiceAddon;
import com.enn3developer.gtnhvoice.api.client.VoiceFormat;
import com.enn3developer.gtnhvoice.client.playback.PlaybackFrame;

/**
 * The frame-aware chain features: {@code when(...)} branches run only on frames whose {@link IPlaybackFrame}
 * matches, keep per-source state and reset when re-entered after a skip, and survive a throwing condition;
 * {@code Stage.beginFrame} hands custom stages each frame's context; capture chains reject both at the call site.
 * Branch state is probed with a high-pass fed DC: its first frame from rest differs from every later one, so
 * "matches a first-ever frame" means "started from fresh state".
 */
class FrameAwareChainTest {

    private static final UUID SPEAKER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SPEAKER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static IPlaybackFrame radio(UUID speaker) {
        return new PlaybackFrame(speaker, "radio", "Radio", false, Double.NaN, Double.NaN, Double.NaN);
    }

    private static IPlaybackFrame local(UUID speaker) {
        return new PlaybackFrame(speaker, "local", "local", true, 0, 64, 0);
    }

    private static boolean isRadio(IPlaybackFrame frame) {
        return "radio".equals(frame.groupName());
    }

    private static short[] dc(double level) {
        short[] frame = new short[VoiceFormat.FRAME_SAMPLES];
        Arrays.fill(frame, PcmSamples.toPcm(level));
        return frame;
    }

    private static ChainPlaybackFilter playbackChain(Consumer<IPcmChain> spec) {
        PcmChainRecorder recorder = PcmChainRecorder.forPlayback("addon");
        spec.accept(recorder);
        return new ChainPlaybackFilter(recorder.compile());
    }

    private static Consumer<IPcmChain> radioHighPass() {
        return c -> c.when(FrameAwareChainTest::isRadio, branch -> branch.highPass(1500));
    }

    @Test
    void branchRunsOnMatchingFramesOnly() {
        ChainPlaybackFilter chain = playbackChain(c -> c.when(FrameAwareChainTest::isRadio, branch -> branch.gain(2.0)));

        short[] fromRadio = chain.process(radio(SPEAKER_A), dc(0.25));
        short[] fromLocal = chain.process(local(SPEAKER_A), dc(0.25));

        assertEquals(PcmSamples.toPcm(0.5), fromRadio[0], "a matching frame goes through the branch");
        assertEquals(PcmSamples.toPcm(0.25), fromLocal[0], "a non-matching frame passes the branch untouched");
    }

    @Test
    void stagesAfterABranchRunOnEveryFrame() {
        ChainPlaybackFilter chain = playbackChain(
            c -> c.when(FrameAwareChainTest::isRadio, branch -> branch.gain(2.0))
                .gain(0.5));

        short[] fromLocal = chain.process(local(SPEAKER_A), dc(0.5));

        assertEquals(PcmSamples.toPcm(0.25), fromLocal[0]);
    }

    @Test
    void eachSpeakerHasItsOwnBranchState() {
        ChainPlaybackFilter chain = playbackChain(radioHighPass());

        short[] aFirst = chain.process(radio(SPEAKER_A), dc(0.5));
        chain.process(radio(SPEAKER_A), dc(0.5)); // advances A's branch state only
        short[] bFirst = chain.process(radio(SPEAKER_B), dc(0.5));

        assertArrayEquals(aFirst, bFirst, "B's branch starts from rest whatever A's branch went through");
    }

    @Test
    void branchReenteredAfterASkippedFrameStartsFromFreshState() {
        ChainPlaybackFilter chain = playbackChain(radioHighPass());

        short[] first = chain.process(radio(SPEAKER_A), dc(0.5));
        short[] second = chain.process(radio(SPEAKER_A), dc(0.5));
        chain.process(local(SPEAKER_A), dc(0.5)); // the branch is skipped...
        short[] reentered = chain.process(radio(SPEAKER_A), dc(0.5)); // ...and re-entered

        assertFalse(Arrays.equals(first, second), "consecutive matches carry the branch state forward");
        assertArrayEquals(first, reentered, "re-entry rebuilds the branch, so it answers like a first frame");
    }

    @Test
    void throwingConditionSkipsOnlyItsOwnBranch() {
        ChainPlaybackFilter chain = playbackChain(c -> c.when(frame -> {
            throw new IllegalStateException("addon bug");
        }, branch -> branch.gain(2.0))
            .when(FrameAwareChainTest::isRadio, branch -> branch.gain(0.5)));

        short[] out = chain.process(radio(SPEAKER_A), dc(0.5));

        assertEquals(PcmSamples.toPcm(0.25), out[0], "the broken branch is skipped, the next one still runs");
    }

    @Test
    void branchRecordingNoStagesIsRejectedAtTheCallSite() {
        PcmChainRecorder recorder = PcmChainRecorder.forPlayback("addon");

        assertThrows(IllegalArgumentException.class, () -> recorder.when(frame -> true, branch -> {}));
    }

    @Test
    void customStageSeesEachFramesContextBeforeItsSamples() {
        List<String> calls = new ArrayList<>();
        ChainPlaybackFilter chain = playbackChain(c -> c.stage(() -> new IPcmChain.Stage() {

            private double gain;

            @Override
            public void beginFrame(IPlaybackFrame frame) {
                calls.add("begin:" + frame.groupName());
                gain = isRadio(frame) ? 0.5 : 1.0;
            }

            @Override
            public double step(double x) {
                return x * gain;
            }
        }));

        short[] fromRadio = chain.process(radio(SPEAKER_A), dc(0.5));
        short[] fromLocal = chain.process(local(SPEAKER_A), dc(0.5));

        assertEquals(List.of("begin:radio", "begin:local"), calls, "exactly once per frame");
        assertEquals(PcmSamples.toPcm(0.25), fromRadio[0], "the radio frame's samples used the radio gain");
        assertEquals(PcmSamples.toPcm(0.5), fromLocal[0], "the local frame's samples used the local gain");
    }

    @Test
    void stageInsideABranchOnlyBeginsFramesTheBranchRuns() {
        List<String> begun = new ArrayList<>();
        ChainPlaybackFilter chain = playbackChain(
            c -> c.when(FrameAwareChainTest::isRadio, branch -> branch.stage(() -> new IPcmChain.Stage() {

                @Override
                public void beginFrame(IPlaybackFrame frame) {
                    begun.add(frame.groupName());
                }

                @Override
                public double step(double x) {
                    return x;
                }
            })));

        chain.process(radio(SPEAKER_A), dc(0.5));
        chain.process(local(SPEAKER_A), dc(0.5));

        assertEquals(List.of("radio"), begun);
    }

    @Test
    void captureChainRejectsWhen() {
        IVoiceAddon addon = new ClientApiBackend().newAddonBuilder("addon")
            .register();

        assertThrows(
            IllegalArgumentException.class,
            () -> addon.capture()
                .chain(c -> c.when(frame -> true, branch -> branch.gain(2.0))));
    }

    @Test
    void captureChainRejectsAStageThatOverridesBeginFrame() {
        IVoiceAddon addon = new ClientApiBackend().newAddonBuilder("addon")
            .register();

        assertThrows(
            IllegalArgumentException.class,
            () -> addon.capture()
                .chain(c -> c.stage(() -> new IPcmChain.Stage() {

                    @Override
                    public void beginFrame(IPlaybackFrame frame) {}

                    @Override
                    public double step(double x) {
                        return x;
                    }
                })));
    }

    @Test
    void captureChainStillAcceptsAFrameBlindCustomStage() {
        IVoiceAddon addon = new ClientApiBackend().newAddonBuilder("addon")
            .register();

        assertDoesNotThrow(
            () -> addon.capture()
                .chain(c -> c.stage(() -> x -> x)),
            "a stage that never reads frame context records on capture as before");
    }
}
