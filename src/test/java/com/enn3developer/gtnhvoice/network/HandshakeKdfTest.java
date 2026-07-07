package com.enn3developer.gtnhvoice.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.core.proto.packets.PacketUtil;

/**
 * Covers the v4 ephemeral X25519 handshake and its HKDF-SHA256 key derivation:
 * <ul>
 * <li>raw 32-byte public keys survive the wire round trip and both sides derive the SAME AES key
 * from the shared secret (the "known keypair vector" both ends must agree on);</li>
 * <li>the AES key is a pure function of the ECDH shared secret and the sessionId NEVER feeds key
 * derivation - the exact vulnerability class v4 exists to kill (the old scheme let anyone who
 * sniffed the cleartext session token recompute the key);</li>
 * <li>the HKDF implementation matches an independent reference that itself passes RFC 5869.</li>
 * </ul>
 */
class HandshakeKdfTest {

    // Same fixed HKDF salt/info strings VoiceProtocol.deriveKey uses; duplicated here on purpose so a
    // silent change to either constant is caught by hkdfMatchesReferenceImplementation.
    private static final byte[] HKDF_SALT = "gtnh-voice x25519 v4 salt".getBytes();
    private static final byte[] HKDF_INFO = "gtnh-voice udp aes-256-gcm key v4".getBytes();

    @Test
    void publicKeyRawEncodingRoundTrips() {
        KeyPair pair = VoiceProtocol.generateEphemeralKeyPair();
        byte[] raw = VoiceProtocol.encodePublicKey(pair.getPublic());
        assertEquals(VoiceProtocol.X25519_PUBLIC_KEY_LENGTH, raw.length, "raw X25519 key must be 32 bytes");

        PublicKey decoded = VoiceProtocol.decodePublicKey(raw);
        assertArrayEquals(raw, VoiceProtocol.encodePublicKey(decoded), "decode(encode(k)) must round-trip");
    }

    @Test
    void bothSidesDeriveTheSameKeyThroughTheWireEncoding() {
        // Mirrors the real handshake: each side keeps its private key, ships only the 32 raw public
        // bytes, and reconstructs the peer key from those bytes before agreeing.
        KeyPair client = VoiceProtocol.generateEphemeralKeyPair();
        KeyPair server = VoiceProtocol.generateEphemeralKeyPair();

        byte[] clientPubRaw = VoiceProtocol.encodePublicKey(client.getPublic());
        byte[] serverPubRaw = VoiceProtocol.encodePublicKey(server.getPublic());

        byte[] serverShared = VoiceProtocol
            .computeSharedSecret(server.getPrivate(), VoiceProtocol.decodePublicKey(clientPubRaw));
        byte[] clientShared = VoiceProtocol
            .computeSharedSecret(client.getPrivate(), VoiceProtocol.decodePublicKey(serverPubRaw));

        assertArrayEquals(serverShared, clientShared, "X25519 ECDH must agree on both sides");

        byte[] serverKey = VoiceProtocol.deriveKey(serverShared);
        byte[] clientKey = VoiceProtocol.deriveKey(clientShared);

        assertEquals(32, serverKey.length, "derived key must be AES-256 (32 bytes)");
        assertArrayEquals(serverKey, clientKey, "both sides must derive an identical AES key");
    }

    @Test
    void derivationIsDeterministicButDistinctPerSharedSecret() {
        byte[] sharedA = VoiceProtocol.computeSharedSecret(
            VoiceProtocol.generateEphemeralKeyPair()
                .getPrivate(),
            VoiceProtocol.generateEphemeralKeyPair()
                .getPublic());

        assertArrayEquals(VoiceProtocol.deriveKey(sharedA), VoiceProtocol.deriveKey(sharedA),
            "same shared secret must derive the same key");

        byte[] sharedB = VoiceProtocol.computeSharedSecret(
            VoiceProtocol.generateEphemeralKeyPair()
                .getPrivate(),
            VoiceProtocol.generateEphemeralKeyPair()
                .getPublic());
        assertFalse(Arrays.equals(VoiceProtocol.deriveKey(sharedA), VoiceProtocol.deriveKey(sharedB)),
            "different shared secrets must derive different keys");
    }

    /**
     * The core security invariant: the sessionId (a public token that travels in cleartext in the
     * UDP header) must never influence the AES key. This pins that {@code deriveKey} takes only the
     * shared secret, and that the derived key is not any function of the sessionId alone - the exact
     * regression that the old {@code deriveKey(UUID)} scheme was.
     */
    @Test
    void sessionIdNeverFeedsKeyDerivation() {
        // 1. There is no deriveKey overload that accepts a UUID/sessionId; the only one takes byte[].
        boolean sawByteArrayDerive = false;
        for (Method m : VoiceProtocol.class.getDeclaredMethods()) {
            if (!m.getName()
                .equals("deriveKey")) continue;
            Class<?>[] params = m.getParameterTypes();
            for (Class<?> p : params) {
                assertNotEquals(UUID.class, p, "deriveKey must not accept a sessionId/UUID");
            }
            if (params.length == 1 && params[0] == byte[].class) sawByteArrayDerive = true;
        }
        assertTrue(sawByteArrayDerive, "deriveKey(byte[] sharedSecret) must exist");

        // 2. The derived key is not derivable from the sessionId alone - concretely, it is not the
        // legacy SHA-256(sessionId) (or its truncation) that the pre-v4 vulnerability used as the key.
        byte[] sharedSecret = VoiceProtocol.computeSharedSecret(
            VoiceProtocol.generateEphemeralKeyPair()
                .getPrivate(),
            VoiceProtocol.generateEphemeralKeyPair()
                .getPublic());
        byte[] key = VoiceProtocol.deriveKey(sharedSecret);

        UUID sessionId = UUID.randomUUID();
        byte[] legacyKeyFromSessionId = sha256(PacketUtil.getUUIDBytes(sessionId));
        assertFalse(Arrays.equals(key, legacyKeyFromSessionId), "key must not equal SHA-256(sessionId)");
        assertFalse(Arrays.equals(key, Arrays.copyOf(legacyKeyFromSessionId, 16)),
            "key must not equal the legacy truncated SHA-256(sessionId)");
    }

    @Test
    void deriveKeyMatchesHkdfOfSharedSecret() {
        byte[] sharedSecret = VoiceProtocol.computeSharedSecret(
            VoiceProtocol.generateEphemeralKeyPair()
                .getPrivate(),
            VoiceProtocol.generateEphemeralKeyPair()
                .getPublic());

        byte[] expected = referenceHkdf(HKDF_SALT, sharedSecret, HKDF_INFO, 32);
        assertArrayEquals(expected, VoiceProtocol.deriveKey(sharedSecret),
            "deriveKey must be HKDF-SHA256(salt, sharedSecret, info, 32)");
    }

    @Test
    void referenceHkdfMatchesRfc5869TestCase1() {
        // RFC 5869 Appendix A.1 - validates the reference HKDF this test compares deriveKey against.
        byte[] ikm = HexFormat.of()
            .parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = HexFormat.of()
            .parseHex("000102030405060708090a0b0c");
        byte[] info = HexFormat.of()
            .parseHex("f0f1f2f3f4f5f6f7f8f9");
        byte[] expectedOkm = HexFormat.of()
            .parseHex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");

        assertArrayEquals(expectedOkm, referenceHkdf(salt, ikm, info, 42));
    }

    @Test
    void decodeRejectsWrongLengthPublicKey() {
        assertThrows(IllegalArgumentException.class, () -> VoiceProtocol.decodePublicKey(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> VoiceProtocol.decodePublicKey(new byte[33]));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // Independent RFC 5869 HKDF-SHA256 reference used only by this test.
    private static byte[] referenceHkdf(byte[] salt, byte[] ikm, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            int hashLen = mac.getMacLength();

            mac.init(new SecretKeySpec(salt.length == 0 ? new byte[hashLen] : salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);

            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] okm = new byte[length];
            byte[] previous = new byte[0];
            int copied = 0;
            for (int counter = 1; copied < length; counter++) {
                mac.reset();
                mac.update(previous);
                mac.update(info);
                mac.update((byte) counter);
                previous = mac.doFinal();
                int toCopy = Math.min(hashLen, length - copied);
                System.arraycopy(previous, 0, okm, copied, toCopy);
                copied += toCopy;
            }
            return okm;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
