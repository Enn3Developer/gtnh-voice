package com.enn3developer.gtnhvoice.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Exercises {@link CapturePcmFilterChain} directly against the {@link CapturePcmFilter} contract:
 * registration-order chaining, frame delivery, and isolation of throwing/null/wrong-length filters. No
 * session or worker needed - the chain is a plain object; its integration point (denoise -&gt; chain -&gt;
 * gate inside {@code CaptureSendWorker.run()}) needs a live mic pipeline and is documented in that worker's
 * class javadoc instead.
 */
class CapturePcmFilterChainTest {

    private static final int FRAME_SAMPLES = 960;

    private static short[] frameFilledWith(short value) {
        short[] frame = new short[FRAME_SAMPLES];
        Arrays.fill(frame, value);
        return frame;
    }

    @Test
    void emptyChainReturnsTheSameArrayUntouched() {
        CapturePcmFilterChain chain = new CapturePcmFilterChain();
        short[] frame = frameFilledWith((short) 7);

        // Same array identity - the no-filter path must not copy or transform.
        assertSame(frame, chain.apply(frame));
        assertEquals(7, frame[0]);
    }

    @Test
    void singleFilterSeesTheFrameAndItsOutputIsReturned() {
        CapturePcmFilterChain chain = new CapturePcmFilterChain();
        short[] submitted = frameFilledWith((short) 3);
        short[] replacement = frameFilledWith((short) 9);
        List<short[]> seenFrames = new ArrayList<>();
        chain.add(frame -> {
            seenFrames.add(frame);
            return replacement;
        });

        short[] result = chain.apply(submitted);

        assertEquals(1, seenFrames.size());
        assertSame(submitted, seenFrames.get(0));
        assertSame(replacement, result);
    }

    @Test
    void twoFiltersApplyInRegistrationOrder() {
        CapturePcmFilterChain chain = new CapturePcmFilterChain();
        chain.add(frame -> {
            frame[0] += 1;
            return frame;
        });
        chain.add(frame -> {
            frame[0] *= 10;
            return frame;
        });

        short[] result = chain.apply(frameFilledWith((short) 0));

        // (0 + 1) * 10 - the reverse order would give 0 * 10 + 1 = 1.
        assertEquals(10, result[0]);
    }

    @Test
    void throwingFilterIsSkippedAndTheRestOfTheChainStillApplies() {
        CapturePcmFilterChain chain = new CapturePcmFilterChain();
        chain.add(frame -> { throw new IllegalStateException("boom"); });
        chain.add(frame -> {
            frame[0] = 42;
            return frame;
        });

        short[] result = chain.apply(frameFilledWith((short) 0));

        assertEquals(42, result[0]);
    }

    @Test
    void nullReturningFilterIsSkippedLikewise() {
        CapturePcmFilterChain chain = new CapturePcmFilterChain();
        chain.add(frame -> null);
        chain.add(frame -> {
            frame[0] = 42;
            return frame;
        });

        short[] result = chain.apply(frameFilledWith((short) 0));

        assertEquals(42, result[0]);
    }

    @Test
    void wrongLengthReturningFilterIsSkippedAndTheOriginalFrameContinues() {
        CapturePcmFilterChain chain = new CapturePcmFilterChain();
        short[] submitted = frameFilledWith((short) 5);
        chain.add(frame -> new short[123]);

        assertSame(submitted, chain.apply(submitted));
    }

    @Test
    void removedFilterNoLongerRuns() {
        CapturePcmFilterChain chain = new CapturePcmFilterChain();
        CapturePcmFilter filter = frame -> {
            frame[0] = 42;
            return frame;
        };
        chain.add(filter);
        chain.remove(filter);

        short[] submitted = frameFilledWith((short) 5);
        short[] result = chain.apply(submitted);

        assertSame(submitted, result);
        assertEquals(5, result[0]);
    }
}
