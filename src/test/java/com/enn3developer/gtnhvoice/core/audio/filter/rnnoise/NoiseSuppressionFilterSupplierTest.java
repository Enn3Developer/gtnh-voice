package com.enn3developer.gtnhvoice.core.audio.filter.rnnoise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.plasmoverse.rnnoise.Denoise;

/**
 * Exercises {@link NoiseSuppressionFilterSupplier}'s guarded native-load decision. Unlike Opus,
 * there's no pure-Java denoiser to fall back to - every "skip" path here must resolve to
 * {@link Optional#empty()} so the caller (see {@code CaptureSendWorker}) simply sends captured
 * audio unprocessed rather than crashing.
 */
class NoiseSuppressionFilterSupplierTest {

    private static final int FRAME_SIZE = 960; // 20ms @ 48kHz mono, this project's capture frame size

    @AfterEach
    void resetSupplierState() {
        System.clearProperty(NoiseSuppressionFilterSupplier.DISABLE_NATIVES_PROPERTY);
        NoiseSuppressionFilterSupplier.resetForTesting();
    }

    @Test
    void createsWorkingNativeFilterWhenEnabledAndAvailable() throws Exception {
        Optional<NoiseSuppressionFilter> filter = NoiseSuppressionFilterSupplier.create(true);
        assertTrue(filter.isPresent(), "Expected native RNNoise filter to be available on this platform");

        try (NoiseSuppressionFilter noiseSuppressionFilter = filter.get()) {
            short[] noisyFrame = new short[FRAME_SIZE];
            Random random = new Random(42);
            for (int i = 0; i < noisyFrame.length; i++) {
                noisyFrame[i] = (short) random.nextInt(Short.MAX_VALUE);
            }

            short[] processed = noiseSuppressionFilter.process(noisyFrame);

            assertEquals(
                FRAME_SIZE,
                processed.length,
                "Denoised frame must bridge RNNoise's internal 480-sample window back to a full 960-sample frame");
        }
    }

    @Test
    void returnsEmptyWhenDisabledByConfig() {
        Optional<NoiseSuppressionFilter> filter = NoiseSuppressionFilterSupplier.create(false);

        assertFalse(filter.isPresent(), "Config-disabled denoising must not construct a native filter");
    }

    @Test
    void returnsEmptyWhenDisabledBySystemProperty() {
        System.setProperty(NoiseSuppressionFilterSupplier.DISABLE_NATIVES_PROPERTY, "true");

        Optional<NoiseSuppressionFilter> filter = NoiseSuppressionFilterSupplier.create(true);

        assertFalse(filter.isPresent(), "The disable-natives escape hatch must force denoising off");
    }

    @Test
    void returnsEmptyAndStaysStickyWhenNativeLoadFails() {
        Callable<Denoise> throwingNativeFactory = () -> {
            throw new UnsatisfiedLinkError("simulated missing native library");
        };

        Optional<NoiseSuppressionFilter> firstAttempt = NoiseSuppressionFilterSupplier.create(true, throwingNativeFactory);
        assertFalse(firstAttempt.isPresent(), "A failed native load must resolve to no filter, not throw");

        Callable<Denoise> factoryThatWouldNowSucceed = Denoise::create;
        Optional<NoiseSuppressionFilter> secondAttempt = NoiseSuppressionFilterSupplier
            .create(true, factoryThatWouldNowSucceed);
        assertFalse(
            secondAttempt.isPresent(),
            "Once a native load fails in this JVM, later calls must skip straight to unavailable instead of retrying");
    }
}
