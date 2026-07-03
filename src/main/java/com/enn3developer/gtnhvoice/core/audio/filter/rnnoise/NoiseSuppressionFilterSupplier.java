/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.audio.filter.rnnoise;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.plasmoverse.rnnoise.Denoise;

/**
 * Guarded factory for {@link NoiseSuppressionFilter}. Unlike Opus, RNNoise has no pure-Java
 * fallback: either the native library loads and denoising runs, or it doesn't and captured audio
 * is sent through unprocessed - voice itself must keep working either way. Mirrors
 * {@code OpusCodecSupplier}'s native-load discipline: the decision is driven by actually attempting
 * to construct the native denoiser and catching whatever a failed load throws, not by a pre-check.
 * Once a native load fails once in this JVM, later calls skip straight to "unavailable" instead of
 * retrying (and re-logging) a load that's already known to fail.
 * <p>
 * Set the {@value #DISABLE_NATIVES_PROPERTY} system property to {@code true} to force denoising off
 * even when native is available - both a developer escape hatch and how the skip path is exercised
 * deterministically in tests. The user-facing on/off toggle lives in {@code Config} (not here, so
 * this package stays MC/Forge-free and testable in a plain JVM) - callers pass their own
 * "enabled by config" flag into {@link #create(boolean)}.
 */
public final class NoiseSuppressionFilterSupplier {

    public static final String DISABLE_NATIVES_PROPERTY = "gtnhvoice.disableRnnoise";

    private static final Logger LOGGER = LogManager.getLogger(NoiseSuppressionFilterSupplier.class);

    private static final AtomicBoolean NATIVES_FAILED = new AtomicBoolean(false);
    private static final AtomicBoolean SELECTION_LOGGED = new AtomicBoolean(false);

    private NoiseSuppressionFilterSupplier() {}

    /**
     * Attempts to construct a native RNNoise filter. Returns empty when {@code enabledByConfig} is
     * {@code false}, the {@value #DISABLE_NATIVES_PROPERTY} escape hatch is set, or the native
     * library fails to load for any reason (missing platform binary, classloader/link issue, ...) -
     * in every case, the caller should simply skip denoising and keep sending raw captured audio.
     */
    public static Optional<NoiseSuppressionFilter> create(boolean enabledByConfig) {
        return create(enabledByConfig, Denoise::create);
    }

    static Optional<NoiseSuppressionFilter> create(boolean enabledByConfig, Callable<Denoise> nativeFactory) {
        if (!enabledByConfig) {
            logSelectionOnce(false, "disabled in config");
            return Optional.empty();
        }

        if (Boolean.getBoolean(DISABLE_NATIVES_PROPERTY)) {
            logSelectionOnce(false, "disabled via -D" + DISABLE_NATIVES_PROPERTY);
            return Optional.empty();
        }

        if (NATIVES_FAILED.get()) {
            logSelectionOnce(false, "native RNNoise previously failed to load this run (see earlier warning)");
            return Optional.empty();
        }

        try {
            Denoise denoise = nativeFactory.call();
            logSelectionOnce(true, null);
            return Optional.of(new NoiseSuppressionFilter(denoise));
        } catch (Exception | LinkageError e) {
            NATIVES_FAILED.set(true);
            LOGGER.warn(
                "Failed to load native RNNoise denoiser, noise suppression will be skipped (captured audio is sent unprocessed)",
                e);
            logSelectionOnce(false, "native load failed, see warning above");
            return Optional.empty();
        }
    }

    private static void logSelectionOnce(boolean active, String skippedReason) {
        if (!SELECTION_LOGGED.compareAndSet(false, true)) return;

        if (active) {
            LOGGER.info("RNNoise denoising: ACTIVE (native com.plasmoverse.rnnoise loaded)");
        } else {
            LOGGER.info("RNNoise denoising: SKIPPED ({})", skippedReason);
        }
    }

    /**
     * Test-only: clears the sticky native-load-failed flag and the one-time selection log gate so
     * each test can exercise the decision logic from a clean slate.
     */
    static void resetForTesting() {
        NATIVES_FAILED.set(false);
        SELECTION_LOGGED.set(false);
    }
}
