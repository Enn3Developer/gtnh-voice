/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.audio.codec.opus;

import org.concentus.OpusDecoder;
import org.concentus.OpusException;

import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;

public final class JavaOpusDecoder implements BaseOpusDecoder {

    private final int sampleRate;
    private final int channels;
    private final int frameSize;

    private volatile OpusDecoder decoder;
    private volatile short[] buffer;

    public JavaOpusDecoder(int sampleRate, boolean stereo, int frameSize) {
        this.sampleRate = sampleRate;
        this.channels = stereo ? 2 : 1;
        this.frameSize = frameSize;
    }

    @Override
    public short[] decode(byte[] encoded) throws CodecException {
        if (!isOpen()) throw new CodecException("Decoder is not open");

        int result;
        try {
            if (encoded == null || encoded.length == 0) {
                result = decoder.decode(null, 0, 0, buffer, 0, frameSize, false);
            } else {
                result = decoder.decode(encoded, 0, encoded.length, buffer, 0, frameSize, false);
            }
        } catch (OpusException e) {
            throw new CodecException("Failed to decode audio", e);
        } catch (RuntimeException | AssertionError e) {
            // The pure-Java Concentus backend signals malformed frames with a bare AssertionError (its
            // Inlines.OpusAssert is an unconditional if/throw, so it fires even with -da) and occasionally other
            // unchecked RuntimeExceptions - none of which are OpusException. Surface them as the CodecException
            // this contract promises so one malformed frame is a catchable decode failure, not an escaping throwable
            // that kills the caller's poller thread. Error subclasses other than AssertionError (OOM, etc.) are
            // deliberately not caught so genuinely fatal JVM conditions still propagate.
            throw new CodecException("Failed to decode audio", e);
        }

        short[] decoded;
        if (encoded == null || encoded.length == 0) {
            decoded = new short[result];
        } else {
            decoded = new short[result * channels];
        }

        System.arraycopy(buffer, 0, decoded, 0, decoded.length);

        return decoded;
    }

    @Override
    public void open() throws CodecException {
        try {
            this.decoder = new OpusDecoder(sampleRate, channels);
            this.buffer = new short[frameSize * channels];
        } catch (OpusException e) {
            throw new CodecException("Failed to open opus decoder", e);
        }
    }

    @Override
    public void reset() {
        if (!isOpen()) return;

        decoder.resetState();
    }

    @Override
    public synchronized void close() {
        if (decoder == null) return;

        this.decoder = null;
        this.buffer = null;
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
