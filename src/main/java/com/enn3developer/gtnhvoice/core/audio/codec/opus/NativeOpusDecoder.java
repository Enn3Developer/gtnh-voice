/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.audio.codec.opus;

import java.io.IOException;

import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.plasmoverse.opus.OpusDecoder;
import com.plasmoverse.opus.OpusException;

/**
 * Wraps Plasmo's native (JNI) Opus decoder binding. Never construct/open this directly outside
 * this package - go through {@link OpusCodecSupplier}, which is the only place that knows how to
 * fall back to {@link JavaOpusDecoder} when the native library isn't available.
 */
public final class NativeOpusDecoder implements BaseOpusDecoder {

    private final int sampleRate;
    private final int channels;
    private final int frameSize;

    private volatile OpusDecoder decoder;

    NativeOpusDecoder(int sampleRate, boolean stereo, int frameSize) {
        this.sampleRate = sampleRate;
        this.channels = stereo ? 2 : 1;
        this.frameSize = frameSize;
    }

    @Override
    public short[] decode(byte[] encoded) throws CodecException {
        if (!isOpen()) throw new CodecException("Decoder is not open");

        try {
            return decoder.decode(encoded);
        } catch (OpusException e) {
            throw new CodecException("Failed to decode audio", e);
        }
    }

    @Override
    public void open() throws CodecException {
        try {
            this.decoder = OpusDecoder.create(sampleRate, channels == 2, frameSize);
        } catch (OpusException | IOException e) {
            throw new CodecException("Failed to open opus decoder", e);
        }
    }

    @Override
    public void reset() {
        if (!isOpen()) return;

        decoder.reset();
    }

    @Override
    public synchronized void close() {
        OpusDecoder d = decoder;
        if (d == null) return;
        decoder = null;
        d.close();
    }

    @Override
    public boolean isOpen() {
        return decoder != null;
    }

    @Override
    public short[] decodePLC() throws CodecException {
        return decode(null);
    }
}
