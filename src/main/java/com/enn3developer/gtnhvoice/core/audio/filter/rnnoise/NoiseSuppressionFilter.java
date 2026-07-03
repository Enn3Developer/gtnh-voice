/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.audio.filter.rnnoise;

import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.api.util.AudioUtil;
import com.plasmoverse.rnnoise.Denoise;
import com.plasmoverse.rnnoise.DenoiseException;

/**
 * Wraps Plasmo's native (JNI) RNNoise denoiser binding. Never construct this directly - go through
 * {@link NoiseSuppressionFilterSupplier}, which is the only place that knows how to fall back to
 * "no denoising" when the native library isn't available.
 * <p>
 * RNNoise operates on a fixed internal 480-sample/10ms window at 48kHz, distinct from this
 * project's 960-sample/20ms capture frame. That mismatch is bridged entirely inside the native
 * binding - {@link Denoise#process(float[])} accepts and returns an array matching whatever length
 * it's given, chunking internally - so no frame-splitting is needed on the Java side.
 * <p>
 * Not thread-safe: {@link #process(short[])} must only be called from the single capture thread
 * that owns this instance.
 */
public final class NoiseSuppressionFilter implements AutoCloseable {

    private final Denoise denoise;

    NoiseSuppressionFilter(Denoise denoise) {
        this.denoise = denoise;
    }

    /**
     * Denoises a captured PCM frame, returning an array of the same length as {@code samples}.
     */
    public short[] process(short[] samples) throws CodecException {
        try {
            float[] floats = AudioUtil.shortsToFloats(samples);
            return AudioUtil.floatsToShorts(denoise.process(floats));
        } catch (DenoiseException e) {
            throw new CodecException("Failed to denoise captured audio", e);
        }
    }

    @Override
    public void close() {
        denoise.close();
    }
}
