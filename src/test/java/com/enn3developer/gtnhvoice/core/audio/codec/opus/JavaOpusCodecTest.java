package com.enn3developer.gtnhvoice.core.audio.codec.opus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.api.util.AudioUtil;
import com.enn3developer.gtnhvoice.core.proto.data.audio.codec.opus.OpusMode;

/**
 * Standalone round-trip sanity check for the pure-Java Opus codec (Concentus-backed), proving
 * it actually encodes/decodes plausible audio before it's wired into the rest of the mod.
 */
class JavaOpusCodecTest {

    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SIZE = 960; // 20ms @ 48kHz mono
    private static final int MTU_SIZE = 1275; // max Opus frame size per RFC 6716

    @Test
    void encodeDecodeRoundTripProducesPlausibleAudio() throws Exception {
        short[] sineSamples = generateSine(FRAME_SIZE, SAMPLE_RATE, 440.0);

        try (JavaOpusEncoder encoder = new JavaOpusEncoder(SAMPLE_RATE, false, OpusMode.VOIP, MTU_SIZE);
            JavaOpusDecoder decoder = new JavaOpusDecoder(SAMPLE_RATE, false, FRAME_SIZE)) {
            encoder.open();
            decoder.open();

            byte[] encoded = encoder.encode(sineSamples);
            assertTrue(encoded.length > 0, "Encoded Opus packet should not be empty");
            assertTrue(encoded.length <= MTU_SIZE, "Encoded Opus packet should not exceed the MTU");

            short[] decoded = decoder.decode(encoded);
            assertEquals(FRAME_SIZE, decoded.length, "Decoded frame should have the requested sample count");

            double level = AudioUtil.calculateAudioLevel(decoded, 0, decoded.length);
            assertTrue(level > -60.0, "Decoded audio should not be silence, but audio level was " + level + " dB");
        }
    }

    @Test
    void decodePlcSynthesizesAFrameWithoutAnEncodedPacket() throws Exception {

        try (JavaOpusDecoder decoder = new JavaOpusDecoder(SAMPLE_RATE, false, FRAME_SIZE)) {
            decoder.open();

            // Prime the decoder state with a real frame first, PLC on a decoder with no prior state is undefined.
            try (JavaOpusEncoder encoder = new JavaOpusEncoder(SAMPLE_RATE, false, OpusMode.VOIP, MTU_SIZE)) {
                encoder.open();
                decoder.decode(encoder.encode(generateSine(FRAME_SIZE, SAMPLE_RATE, 440.0)));
            }

            short[] concealed = decoder.decodePLC();
            assertEquals(FRAME_SIZE, concealed.length, "PLC frame should have the requested sample count");
        }
    }

    @Test
    void closeIsIdempotentAndDecodingAfterCloseThrows() throws Exception {
        JavaOpusDecoder decoder = new JavaOpusDecoder(SAMPLE_RATE, false, FRAME_SIZE);
        decoder.open();

        decoder.close();
        assertFalse(decoder.isOpen(), "Decoder should not be open after close");

        assertDoesNotThrow(decoder::close, "Calling close a second time should be a no-op");

        assertThrows(
            CodecException.class,
            () -> decoder.decode(new byte[] { 1, 2, 3 }),
            "Decoding after close should throw CodecException");
    }

    private static short[] generateSine(int samples, int sampleRate, double frequencyHz) {
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            double angle = 2.0 * Math.PI * frequencyHz * i / sampleRate;
            pcm[i] = (short) (Math.sin(angle) * Short.MAX_VALUE * 0.8);
        }
        return pcm;
    }
}
