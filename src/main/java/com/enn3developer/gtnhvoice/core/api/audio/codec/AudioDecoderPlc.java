/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.api.audio.codec;

/**
 * Represents an audio decoder capable of packet loss concealment (PLC), synthesizing a
 * plausible frame of audio for a packet that was never received.
 */
public interface AudioDecoderPlc {

    /**
     * Synthesizes a frame of audio to conceal a lost packet.
     *
     * @return An array of audio samples represented as shorts.
     * @throws CodecException If there's an error during the decoding process.
     */
    short[] decodePLC() throws CodecException;
}
