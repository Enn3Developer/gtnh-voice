package com.enn3developer.gtnhvoice.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Pins the CWE-789 fix in {@link HelloCodec#readUtf8String}: an attacker-controlled LEB128 length prefix
 * must be rejected against {@link HelloCodec#MAX_UTF8_STRING_LENGTH} <em>before</em> the {@code new byte[length]}
 * allocation, so a 6-byte hello claiming ~2 GiB can no longer force a giant heap allocation on the server
 * thread. Ordinary tiny strings must still round-trip unchanged.
 */
class HelloCodecTest {

    @Test
    void normalStringRoundTrips() throws IOException {
        assertRoundTrips("");
        assertRoundTrips("1.0.0-gtnhvoice");
        assertRoundTrips("héllo-ö0-世界"); // multibyte UTF-8 survives
    }

    @Test
    void maxLengthStringStillDecodes() throws IOException {
        // A string exactly at the cap is legal - the guard rejects only what is strictly larger.
        assertRoundTrips("x".repeat(HelloCodec.MAX_UTF8_STRING_LENGTH));
    }

    @Test
    void oversizedLengthPrefixIsRejectedBeforeAllocating() {
        // The weapon from Fable's PoC: a length prefix claiming ~1.9 GiB with NO string bytes following.
        // The fix must reject it cheaply on the varint, never reaching new byte[claimedLen].
        byte[] hostile = craftLengthPrefixOnly(1_900_000_000);
        IOException ex = assertThrows(IOException.class, () -> HelloCodec.readUtf8String(dataIn(hostile)));
        // Sanity: it failed on our range check, not on an EOF from readFully of a monster array.
        assertTrue(ex.getMessage().contains("out of range"), ex.getMessage());
    }

    @Test
    void integerMaxLengthPrefixIsRejected() {
        byte[] hostile = craftLengthPrefixOnly(Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> HelloCodec.readUtf8String(dataIn(hostile)));
    }

    @Test
    void justOverCapIsRejected() {
        byte[] hostile = craftLengthPrefixOnly(HelloCodec.MAX_UTF8_STRING_LENGTH + 1);
        assertThrows(IOException.class, () -> HelloCodec.readUtf8String(dataIn(hostile)));
    }

    @Test
    void decodeClientHelloRejectsOversizedModVersion() {
        // The oversized length reaches readUtf8String via the real ClientHello entry point. decodeClientHello
        // wraps the IOException in UncheckedIOException, but the point is it throws instead of allocating.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(VoiceProtocol.PROTOCOL_VERSION);
        writeVarInt(bos, 1_900_000_000);
        assertThrows(RuntimeException.class, () -> HelloCodec.decodeClientHello(bos.toByteArray()));
    }

    private static void assertRoundTrips(String value) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        HelloCodec.writeUtf8String(new DataOutputStream(bos), value);
        String decoded = HelloCodec.readUtf8String(dataIn(bos.toByteArray()));
        assertEquals(value, decoded);
        // And the raw bytes are the ByteBufUtils-compatible framing (varint prefix + UTF-8 bytes).
        byte[] utf8 = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        writeVarInt(expected, utf8.length);
        expected.write(utf8);
        assertArrayEquals(expected.toByteArray(), bos.toByteArray());
    }

    private static DataInputStream dataIn(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    /** A hello fragment that is just an LEB128 length prefix - no string bytes follow it. */
    private static byte[] craftLengthPrefixOnly(int claimedLen) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        writeVarInt(bos, claimedLen);
        return bos.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
