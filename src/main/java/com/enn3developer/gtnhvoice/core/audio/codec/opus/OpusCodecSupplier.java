/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.audio.codec.opus;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.enn3developer.gtnhvoice.core.api.audio.codec.AudioDecoder;
import com.enn3developer.gtnhvoice.core.api.audio.codec.AudioEncoder;
import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.proto.data.audio.codec.opus.OpusMode;

/**
 * Chooses between {@link NativeOpusEncoder}/{@link NativeOpusDecoder} (Plasmo's native JNI Opus
 * binding) and {@link JavaOpusEncoder}/{@link JavaOpusDecoder} (pure-Java, Concentus-backed) at
 * runtime. Native is only ever an optional acceleration: the decision is driven by actually
 * attempting to construct+open the native codec and catching whatever a failed load throws, not
 * just by a pre-check that could be wrong on a given OS/arch/JVM combination. Once a native load
 * fails once in this JVM, later calls skip straight to the Java codec instead of retrying (and
 * re-logging) a load that's already known to fail.
 * <p>
 * Set the {@value #DISABLE_NATIVES_PROPERTY} system property to {@code true} to force the Java
 * codec even when native is available - both a user escape hatch and how the fallback path is
 * exercised deterministically in tests.
 */
public final class OpusCodecSupplier {

    public static final String DISABLE_NATIVES_PROPERTY = "gtnhvoice.disableNatives";

    private static final Logger LOGGER = LogManager.getLogger(OpusCodecSupplier.class);

    private static final AtomicBoolean NATIVES_FAILED = new AtomicBoolean(false);
    private static final AtomicBoolean SELECTION_LOGGED = new AtomicBoolean(false);

    private OpusCodecSupplier() {}

    public static AudioEncoder createEncoder(int sampleRate, boolean stereo, OpusMode opusMode, int mtuSize)
        throws CodecException {
        return createWithFallback(() -> {
            NativeOpusEncoder encoder = new NativeOpusEncoder(sampleRate, stereo, opusMode, mtuSize);
            encoder.open();
            return encoder;
        }, () -> {
            JavaOpusEncoder encoder = new JavaOpusEncoder(sampleRate, stereo, opusMode, mtuSize);
            encoder.open();
            return encoder;
        });
    }

    public static AudioDecoder createDecoder(int sampleRate, boolean stereo, int frameSize) throws CodecException {
        return createWithFallback(() -> {
            NativeOpusDecoder decoder = new NativeOpusDecoder(sampleRate, stereo, frameSize);
            decoder.open();
            return decoder;
        }, () -> {
            JavaOpusDecoder decoder = new JavaOpusDecoder(sampleRate, stereo, frameSize);
            decoder.open();
            return decoder;
        });
    }

    static <T> T createWithFallback(Callable<T> nativeFactory, Callable<T> javaFactory) throws CodecException {
        boolean nativeFailedThisCall = false;

        if (isNativesAllowed()) {
            try {
                T codec = nativeFactory.call();
                logSelectionOnce(true, false);
                return codec;
            } catch (Exception | LinkageError e) {
                NATIVES_FAILED.set(true);
                nativeFailedThisCall = true;
                LOGGER
                    .warn("Failed to load native Opus codec, falling back to pure-Java (Concentus) implementation", e);
            }
        }

        try {
            T codec = javaFactory.call();
            logSelectionOnce(false, nativeFailedThisCall);
            return codec;
        } catch (CodecException e) {
            throw e;
        } catch (Exception e) {
            throw new CodecException("Failed to open java opus codec", e);
        }
    }

    private static boolean isNativesAllowed() {
        if (NATIVES_FAILED.get()) return false;

        return !Boolean.getBoolean(DISABLE_NATIVES_PROPERTY);
    }

    private static void logSelectionOnce(boolean nativeSelected, boolean nativeFailed) {
        if (!SELECTION_LOGGED.compareAndSet(false, true)) return;

        if (nativeSelected) {
            LOGGER.info("Opus codec: selected NATIVE implementation (com.plasmoverse.opus)");
        } else if (nativeFailed) {
            LOGGER.info(
                "Opus codec: selected pure-Java (Concentus) implementation after native load failed (see warning above)");
        } else {
            LOGGER.info("Opus codec: selected pure-Java (Concentus) implementation");
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
