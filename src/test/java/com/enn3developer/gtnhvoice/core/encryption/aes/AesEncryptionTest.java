package com.enn3developer.gtnhvoice.core.encryption.aes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.core.api.encryption.EncryptionException;

/**
 * Coverage for the AES-256-GCM UDP body cipher: authenticated round-trips, the fresh-nonce-per-call
 * layout, and - the whole reason for moving off AES/CBC - that tampering is detected on decrypt
 * rather than silently yielding garbage plaintext.
 */
class AesEncryptionTest {

    private static byte[] key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 7 + 1);
        return key;
    }

    @Test
    void roundTripsArbitraryPayloads() throws Exception {
        AesEncryption enc = new AesEncryption(key());
        for (byte[] plaintext : new byte[][] { {}, { 42 }, "hello voice".getBytes(), new byte[2048] }) {
            byte[] decrypted = enc.decrypt(enc.encrypt(plaintext));
            assertArrayEquals(plaintext, decrypted);
        }
    }

    @Test
    void encryptPrependsFreshNonceEachCall() throws Exception {
        AesEncryption enc = new AesEncryption(key());
        byte[] plaintext = "same message".getBytes();

        byte[] a = enc.encrypt(plaintext);
        byte[] b = enc.encrypt(plaintext);

        // Distinct 12-byte nonces => distinct ciphertexts for identical plaintext (no nonce reuse).
        assertFalse(Arrays.equals(Arrays.copyOf(a, 12), Arrays.copyOf(b, 12)), "nonce must be fresh per call");
        assertFalse(Arrays.equals(a, b), "ciphertext must differ across calls for the same plaintext");

        assertArrayEquals(plaintext, enc.decrypt(a));
        assertArrayEquals(plaintext, enc.decrypt(b));
    }

    @Test
    void tamperedCiphertextFailsAuthentication() throws Exception {
        AesEncryption enc = new AesEncryption(key());
        byte[] encrypted = enc.encrypt("authentic".getBytes());

        byte[] tampered = encrypted.clone();
        tampered[tampered.length - 1] ^= 0x01; // flip a bit in the GCM tag / ciphertext

        assertThrows(EncryptionException.class, () -> enc.decrypt(tampered),
            "GCM must reject a tampered body instead of returning garbage");
    }

    @Test
    void wrongKeyCannotDecrypt() throws Exception {
        byte[] encrypted = new AesEncryption(key())
            .encrypt("secret audio".getBytes());

        byte[] otherKey = key();
        otherKey[0] ^= 0x01;
        assertThrows(EncryptionException.class, () -> new AesEncryption(otherKey).decrypt(encrypted));
    }

    @Test
    void reportsGcmCipherName() {
        assertEquals("AES/GCM/NoPadding", new AesEncryption(key()).getName());
    }

    @Test
    void rejectsWrongLengthKey() {
        assertThrows(IllegalArgumentException.class, () -> new AesEncryption(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> new AesEncryption(new byte[0]));
    }
}
