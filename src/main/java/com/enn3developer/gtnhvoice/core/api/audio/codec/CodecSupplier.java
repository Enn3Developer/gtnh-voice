/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.api.audio.codec;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.data.audio.codec.CodecInfo;

/**
 * Represents a codec supplier for creating audio encoders and decoders of specific codec types.
 *
 * @param <Encoder> The type of the audio encoder to be created.
 * @param <Decoder> The type of the audio decoder to be created.
 */
public interface CodecSupplier<Encoder extends AudioEncoder, Decoder extends AudioDecoder> {

    /**
     * Creates a new audio encoder with the specified parameters.
     *
     * @param codecInfo  The codec information specifying the codec to be used.
     * @param sampleRate The sample rate of the audio data.
     * @param stereo     {@code true} if the audio is in stereo format, {@code false} for mono.
     * @param mtuSize    The maximum transmission unit (MTU) size for network communication.
     * @return An instance of the audio encoder with the specified parameters.
     */
    @NotNull
    Encoder createEncoder(@NotNull CodecInfo codecInfo, int sampleRate, boolean stereo, int mtuSize);

    /**
     * Creates a new audio decoder with the specified parameters.
     *
     * @param codecInfo  The codec information specifying the codec to be used.
     * @param sampleRate The sample rate of the audio data.
     * @param stereo     {@code true} if the audio is in stereo format, {@code false} for mono.
     * @param frameSize  The size of the decoding frame.
     * @return An instance of the audio decoder with the specified parameters.
     */
    @NotNull
    Decoder createDecoder(@NotNull CodecInfo codecInfo, int sampleRate, boolean stereo, int frameSize);

    /**
     * Gets the name of the codec.
     *
     * @return The name of the codec.
     */
    @NotNull
    String getName();
}
