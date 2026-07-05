package com.enn3developer.gtnhvoice.client.playback;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.client.VoiceFormat;

/**
 * Exercises {@link AddonFilterAdapter}'s per-addon isolation and output validation without an AL device or a
 * receive path: a valid frame passes through, a delegate that throws (Errors included), returns {@code null},
 * or returns a wrong-length array is skipped with the INPUT frame passed through unchanged, and two broken
 * adapters keep operating independently. The real chain position inside {@code PlaybackManager#submit} is
 * covered by {@code PlaybackPcmFilterTest}; this adapter guarantees the chain's own isolation never has to
 * fire for wrapped addons.
 */
class AddonFilterAdapterTest {

    private static final UUID SOURCE_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static short[] frame() {
        return new short[VoiceFormat.FRAME_SAMPLES];
    }

    @Test
    void validOutputPassesThrough() {
        short[] processed = frame();
        AddonFilterAdapter adapter = new AddonFilterAdapter("addon", (sourceId, input) -> processed);

        assertSame(processed, adapter.process(SOURCE_A, frame()));
    }

    @Test
    void sourceIdAndFramePassThroughToDelegate() {
        short[] input = frame();
        AddonFilterAdapter adapter = new AddonFilterAdapter("addon", (sourceId, delegateFrame) -> {
            assertEquals(SOURCE_A, sourceId);
            assertSame(input, delegateFrame);
            return delegateFrame;
        });

        assertSame(input, adapter.process(SOURCE_A, input));
    }

    @Test
    void nullOutputPassesInputThroughUnchanged() {
        short[] input = frame();
        AddonFilterAdapter adapter = new AddonFilterAdapter("addon", (sourceId, delegateFrame) -> null);

        assertSame(input, adapter.process(SOURCE_A, input));
    }

    @Test
    void wrongLengthOutputPassesInputThroughUnchanged() {
        short[] input = frame();
        AddonFilterAdapter adapter = new AddonFilterAdapter(
            "addon",
            (sourceId, delegateFrame) -> new short[VoiceFormat.FRAME_SAMPLES / 2]);

        assertSame(input, adapter.process(SOURCE_A, input));
    }

    @Test
    void throwingDelegateIsContainedAndPassesInputThrough() {
        short[] input = frame();
        // The exact failure mode addon filters hit when their class is missing @Lwjgl3Aware.
        AddonFilterAdapter erroring = new AddonFilterAdapter(
            "addon",
            (sourceId, delegateFrame) -> { throw new NoClassDefFoundError("org.lwjgl.openal.AL10"); });
        AddonFilterAdapter excepting = new AddonFilterAdapter(
            "addon",
            (sourceId, delegateFrame) -> { throw new RuntimeException("addon bug"); });

        assertDoesNotThrow(() -> assertSame(input, erroring.process(SOURCE_A, input)));
        assertDoesNotThrow(() -> assertSame(input, excepting.process(SOURCE_A, input)));
    }

    @Test
    void twoBrokenAdaptersDoNotInterfere() {
        // Each adapter carries its OWN throttle: both broken addons keep getting dispatched and contained,
        // repeatedly, with no shared state between them.
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        AddonFilterAdapter adapterA = new AddonFilterAdapter("addon-a", (sourceId, delegateFrame) -> {
            firstCalls.incrementAndGet();
            throw new RuntimeException("addon-a bug");
        });
        AddonFilterAdapter adapterB = new AddonFilterAdapter("addon-b", (sourceId, delegateFrame) -> {
            secondCalls.incrementAndGet();
            return null;
        });

        short[] input = frame();
        for (int i = 0; i < 10; i++) {
            assertSame(input, adapterA.process(SOURCE_A, input));
            assertSame(input, adapterB.process(SOURCE_A, input));
        }

        assertEquals(10, firstCalls.get());
        assertEquals(10, secondCalls.get());
    }
}
