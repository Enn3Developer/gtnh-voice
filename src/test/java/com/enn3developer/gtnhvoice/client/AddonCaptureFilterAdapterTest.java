package com.enn3developer.gtnhvoice.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.client.VoiceFormat;

/**
 * Exercises {@link AddonCaptureFilterAdapter}'s per-addon isolation and output validation - the capture twin
 * of {@code AddonFilterAdapterTest}, minus the sourceId (one local mic). The real chain position inside
 * {@code CaptureSendWorker} is covered by {@code CapturePcmFilterChainTest}; this adapter guarantees the
 * chain's own isolation never has to fire for wrapped addons.
 */
class AddonCaptureFilterAdapterTest {

    private static short[] frame() {
        return new short[VoiceFormat.FRAME_SAMPLES];
    }

    @Test
    void validOutputPassesThrough() {
        short[] processed = frame();
        AddonCaptureFilterAdapter adapter = new AddonCaptureFilterAdapter("addon", input -> processed);

        assertSame(processed, adapter.process(frame()));
    }

    @Test
    void nullOutputPassesInputThroughUnchanged() {
        short[] input = frame();
        AddonCaptureFilterAdapter adapter = new AddonCaptureFilterAdapter("addon", delegateFrame -> null);

        assertSame(input, adapter.process(input));
    }

    @Test
    void wrongLengthOutputPassesInputThroughUnchanged() {
        short[] input = frame();
        AddonCaptureFilterAdapter adapter = new AddonCaptureFilterAdapter(
            "addon",
            delegateFrame -> new short[VoiceFormat.FRAME_SAMPLES + 1]);

        assertSame(input, adapter.process(input));
    }

    @Test
    void throwingDelegateIsContainedAndPassesInputThrough() {
        short[] input = frame();
        AddonCaptureFilterAdapter erroring = new AddonCaptureFilterAdapter(
            "addon",
            delegateFrame -> { throw new NoClassDefFoundError("org.lwjgl.openal.AL10"); });
        AddonCaptureFilterAdapter excepting = new AddonCaptureFilterAdapter(
            "addon",
            delegateFrame -> { throw new RuntimeException("addon bug"); });

        assertDoesNotThrow(() -> assertSame(input, erroring.process(input)));
        assertDoesNotThrow(() -> assertSame(input, excepting.process(input)));
    }

    @Test
    void twoBrokenAdaptersDoNotInterfere() {
        // Each adapter carries its OWN throttle: both broken addons keep getting dispatched and contained,
        // repeatedly, with no shared state between them.
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        AddonCaptureFilterAdapter adapterA = new AddonCaptureFilterAdapter("addon-a", delegateFrame -> {
            firstCalls.incrementAndGet();
            throw new RuntimeException("addon-a bug");
        });
        AddonCaptureFilterAdapter adapterB = new AddonCaptureFilterAdapter("addon-b", delegateFrame -> {
            secondCalls.incrementAndGet();
            return null;
        });

        short[] input = frame();
        for (int i = 0; i < 10; i++) {
            assertSame(input, adapterA.process(input));
            assertSame(input, adapterB.process(input));
        }

        assertEquals(10, firstCalls.get());
        assertEquals(10, secondCalls.get());
    }
}
