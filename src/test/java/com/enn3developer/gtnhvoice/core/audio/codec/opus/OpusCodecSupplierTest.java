package com.enn3developer.gtnhvoice.core.audio.codec.opus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.core.api.audio.codec.AudioDecoder;
import com.enn3developer.gtnhvoice.core.api.audio.codec.AudioEncoder;
import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;
import com.enn3developer.gtnhvoice.core.proto.data.audio.codec.opus.OpusMode;

/**
 * Exercises {@link OpusCodecSupplier}'s native-vs-Java decision and its fallback when a native
 * load fails, without relying on the log output to tell which path was actually taken - each
 * assertion checks the concrete type of the codec instance the supplier handed back.
 */
class OpusCodecSupplierTest {

    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SIZE = 960; // 20ms @ 48kHz mono
    private static final int MTU_SIZE = 1275; // max Opus frame size per RFC 6716

    @AfterEach
    void resetSupplierState() {
        System.clearProperty(OpusCodecSupplier.DISABLE_NATIVES_PROPERTY);
        OpusCodecSupplier.resetForTesting();
    }

    @Test
    void selectsNativeAndRoundTripsWhenNativesAreAvailable() throws Exception {
        try (AudioEncoder encoder = OpusCodecSupplier.createEncoder(SAMPLE_RATE, false, OpusMode.VOIP, MTU_SIZE);
            AudioDecoder decoder = OpusCodecSupplier.createDecoder(SAMPLE_RATE, false, FRAME_SIZE)) {
            assertInstanceOf(NativeOpusEncoder.class, encoder, "Expected native Opus encoder to be selected");
            assertInstanceOf(NativeOpusDecoder.class, decoder, "Expected native Opus decoder to be selected");

            roundTrip(encoder, decoder);
        }
    }

    @Test
    void selectsJavaAndRoundTripsWhenNativesAreDisabled() throws Exception {
        System.setProperty(OpusCodecSupplier.DISABLE_NATIVES_PROPERTY, "true");

        try (AudioEncoder encoder = OpusCodecSupplier.createEncoder(SAMPLE_RATE, false, OpusMode.VOIP, MTU_SIZE);
            AudioDecoder decoder = OpusCodecSupplier.createDecoder(SAMPLE_RATE, false, FRAME_SIZE)) {
            assertInstanceOf(JavaOpusEncoder.class, encoder, "Expected pure-Java Opus encoder when natives disabled");
            assertInstanceOf(JavaOpusDecoder.class, decoder, "Expected pure-Java Opus decoder when natives disabled");

            roundTrip(encoder, decoder);
        }
    }

    @Test
    void fallsBackToJavaWhenNativeLoadThrowsUnsatisfiedLinkError() throws Exception {
        Callable<AudioEncoder> throwingNativeFactory = () -> {
            throw new UnsatisfiedLinkError("simulated missing native library");
        };
        Callable<AudioEncoder> javaFactory = () -> {
            JavaOpusEncoder encoder = new JavaOpusEncoder(SAMPLE_RATE, false, OpusMode.VOIP, MTU_SIZE);
            encoder.open();
            return encoder;
        };

        try (AudioEncoder encoder = OpusCodecSupplier.createWithFallback(throwingNativeFactory, javaFactory)) {
            assertInstanceOf(JavaOpusEncoder.class, encoder, "Expected fallback to the pure-Java encoder");
            assertTrue(encoder.isOpen(), "Fallback encoder should be open and usable");

            byte[] encoded = encoder.encode(new short[FRAME_SIZE]);
            assertTrue(encoded.length > 0, "Fallback encoder should actually work, not just construct");
        }
    }

    @Test
    void fallsBackToJavaWhenNativeConstructorThrowsLinkageError() throws Exception {
        Callable<AudioDecoder> throwingNativeFactory = () -> {
            throw new LinkageError("simulated native/java ABI mismatch");
        };
        Callable<AudioDecoder> javaFactory = () -> {
            JavaOpusDecoder decoder = new JavaOpusDecoder(SAMPLE_RATE, false, FRAME_SIZE);
            decoder.open();
            return decoder;
        };

        try (AudioDecoder decoder = OpusCodecSupplier.createWithFallback(throwingNativeFactory, javaFactory)) {
            assertInstanceOf(JavaOpusDecoder.class, decoder, "Expected fallback to the pure-Java decoder");
            assertTrue(decoder.isOpen(), "Fallback decoder should be open and usable");
        }
    }

    @Test
    void propagatesCodecExceptionWhenBothNativeAndJavaFail() {
        Callable<AudioEncoder> throwingNativeFactory = () -> {
            throw new UnsatisfiedLinkError("simulated missing native library");
        };
        Callable<AudioEncoder> throwingJavaFactory = () -> {
            throw new CodecException("simulated java codec failure");
        };

        assertThrows(
            CodecException.class,
            () -> OpusCodecSupplier.createWithFallback(throwingNativeFactory, throwingJavaFactory));
    }

    private static void roundTrip(AudioEncoder encoder, AudioDecoder decoder) throws CodecException {
        short[] silence = new short[FRAME_SIZE];
        byte[] encoded = encoder.encode(silence);
        assertTrue(encoded.length > 0, "Encoded Opus packet should not be empty");

        short[] decoded = decoder.decode(encoded);
        assertEquals(FRAME_SIZE, decoded.length, "Decoded frame should have the requested sample count");
    }
}
