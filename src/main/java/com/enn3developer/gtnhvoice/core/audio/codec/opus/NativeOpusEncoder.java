/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.audio.codec.opus;

import java.io.IOException;
import java.util.Arrays;

import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.proto.data.audio.codec.opus.OpusMode;
import com.plasmoverse.opus.OpusEncoder;
import com.plasmoverse.opus.OpusException;

/**
 * Wraps Plasmo's native (JNI) Opus encoder binding. Never construct/open this directly outside
 * this package - go through {@link OpusCodecSupplier}, which is the only place that knows how to
 * fall back to {@link JavaOpusEncoder} when the native library isn't available.
 */
public final class NativeOpusEncoder implements BaseOpusEncoder {

    private final int sampleRate;
    private final int channels;
    private final OpusMode opusMode;
    private final int mtuSize;

    private OpusEncoder encoder;

    NativeOpusEncoder(int sampleRate, boolean stereo, OpusMode opusMode, int mtuSize) {
        this.sampleRate = sampleRate;
        this.channels = stereo ? 2 : 1;
        this.opusMode = opusMode;
        this.mtuSize = mtuSize;
    }

    @Override
    public byte[] encode(short[] samples) throws CodecException {
        if (!isOpen()) throw new CodecException("Encoder is not open");

        try {
            return encoder.encode(samples);
        } catch (OpusException e) {
            throw new CodecException("Failed to encode audio", e);
        }
    }

    @Override
    public void open() throws CodecException {
        try {
            com.plasmoverse.opus.OpusMode mode = Arrays.stream(com.plasmoverse.opus.OpusMode.values())
                .filter(element -> element.getApplication() == opusMode.getApplication())
                .findFirst()
                .orElseThrow(() -> new CodecException("Invalid opus application mode"));

            this.encoder = OpusEncoder.create(sampleRate, channels == 2, mtuSize, mode);
        } catch (OpusException | IOException e) {
            throw new CodecException("Failed to open opus encoder", e);
        }
    }

    @Override
    public void reset() {
        if (!isOpen()) return;

        encoder.reset();
    }

    @Override
    public void close() {
        if (!isOpen()) return;

        encoder.close();
        this.encoder = null;
    }

    @Override
    public boolean isOpen() {
        return encoder != null;
    }

    @Override
    public void setBitrate(int bitrate) {
        if (!isOpen()) return;

        encoder.setBitrate(bitrate);
    }

    @Override
    public int getBitrate() {
        if (!isOpen()) return -1;

        try {
            return encoder.getBitrate();
        } catch (OpusException e) {
            return -1;
        }
    }
}
