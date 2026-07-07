package com.enn3developer.gtnhvoice.network;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared constants and crypto helpers for the reliable control-channel handshake. Referenced from
 * both {@code server} and {@code client} packages so the two sides can never drift apart on how the
 * UDP encryption key is negotiated.
 * <p>
 * <b>Key exchange.</b> The two sides run an <em>ephemeral</em> X25519 ECDH handshake (JDK-native
 * {@code XDH}, no external crypto deps): each end generates a throwaway keypair per connection,
 * exchanges only its 32-byte raw public key over the control channel, and derives the AES-256 UDP
 * key from the shared secret via HKDF-SHA256. No key material - neither the ECDH secret nor the
 * derived key - ever travels over the wire in recoverable form, on any channel. The session's
 * {@code sessionId} UUID is only a public token (UDP header + server session lookup) and is
 * deliberately <b>never</b> fed into key derivation - see {@link #deriveKey(byte[])}.
 * <p>
 * <b>Security scope / known limit.</b> This is <em>unauthenticated</em> ECDH. It defeats passive
 * eavesdropping on both the TCP (FML/control) and UDP legs and gives forward secrecy (ephemeral
 * keys, discarded after the session), but it does <b>not</b> stop an active man-in-the-middle who
 * can rewrite the control channel - such an attacker could substitute its own public keys and
 * establish two half-sessions. Authenticating the exchange (e.g. a persistent server EC identity
 * key that signs the ephemeral pubkey, plus client trust-on-first-use/pinning) would close that
 * gap. TODO: add server identity signing + client TOFU pinning.
 */
public final class VoiceProtocol {

    public static final String CHANNEL = "gtnhvoice";
    // v2: SourceAudioPacket now carries the speaker's absolute position (x,y,z) instead of a
    // scalar distance, so a mismatched client/server pair must fail the handshake cleanly rather
    // than misreading UDP audio packet bytes.
    // v3: adds VoiceGroupUpdatePacket, always sent on the FML control channel; an old client would
    // fail in the codec on the unknown discriminator, so the handshake must turn the skew into a
    // clean reject + chat message instead.
    // v4: replaces the old "the session secret IS the key" scheme with an ephemeral X25519 ECDH
    // handshake + HKDF-SHA256 key derivation, and the UDP body cipher moves from unauthenticated
    // AES/CBC to AES/GCM. ClientHello and ServerHello now each carry a 32-byte raw X25519 public
    // key, so an older peer must fail the handshake rather than misparse the new body.
    public static final byte PROTOCOL_VERSION = 4;

    public static final byte REASON_VERSION_MISMATCH = 0;
    /** The protocol versions matched but the peer's ClientHello carried no usable X25519 public key. */
    public static final byte REASON_HANDSHAKE_FAILED = 1;

    /** X25519 raw public keys are always exactly 32 bytes (RFC 7748 u-coordinate, little-endian). */
    public static final int X25519_PUBLIC_KEY_LENGTH = 32;

    // AES-256 for the UDP body cipher (see AesEncryption).
    private static final int AES_KEY_LENGTH_BYTES = 32;

    // HKDF-SHA256 salt/info. Both are fixed, non-secret, protocol-versioned context strings shared
    // by client and server. The salt is intentionally NOT the sessionId: the ephemeral shared
    // secret is already unique per session, and the sessionId travels in cleartext, so letting it
    // influence the key would reintroduce exactly the "sniff the token, get the key" class of bug
    // this handshake exists to kill. Domain separation comes from the protocol-versioned strings.
    private static final byte[] HKDF_SALT = "gtnh-voice x25519 v4 salt".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HKDF_INFO = "gtnh-voice udp aes-256-gcm key v4".getBytes(StandardCharsets.US_ASCII);

    // DER SubjectPublicKeyInfo prefix for an X25519 public key: SEQUENCE { AlgorithmIdentifier {
    // OID 1.3.101.110 }, BIT STRING (33 bytes: 0 unused bits + 32-byte key) }. Prepending this to
    // the 32 raw key bytes yields the 44-byte X.509 encoding KeyFactory("XDH") consumes, and
    // stripping it off getEncoded() recovers the raw bytes - the JDK-to-JDK symmetric encoding.
    private static final byte[] X25519_SPKI_PREFIX = { 0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03,
        0x21, 0x00 };

    /**
     * Generates a fresh ephemeral X25519 keypair for one connection attempt. The private key never
     * leaves the process; only {@link #encodePublicKey(PublicKey)} of the public half is sent.
     */
    public static KeyPair generateEphemeralKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("XDH");
            generator.initialize(NamedParameterSpec.X25519);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            // XDH/X25519 is a mandatory JDK algorithm since Java 11; GTNH runs on Java 17+.
            throw new IllegalStateException("X25519 (XDH) not available", e);
        }
    }

    /** Encodes an X25519 public key to its 32-byte raw form for transmission in a handshake packet. */
    public static byte[] encodePublicKey(PublicKey key) {
        byte[] spki = key.getEncoded();
        if (spki.length != X25519_SPKI_PREFIX.length + X25519_PUBLIC_KEY_LENGTH) {
            throw new IllegalStateException("Unexpected X25519 SubjectPublicKeyInfo length " + spki.length);
        }
        return Arrays.copyOfRange(spki, spki.length - X25519_PUBLIC_KEY_LENGTH, spki.length);
    }

    /** Reconstructs an X25519 public key from the 32 raw bytes carried in a handshake packet. */
    public static PublicKey decodePublicKey(byte[] raw) {
        if (raw == null || raw.length != X25519_PUBLIC_KEY_LENGTH) {
            throw new IllegalArgumentException(
                "X25519 public key must be " + X25519_PUBLIC_KEY_LENGTH + " bytes");
        }

        byte[] spki = new byte[X25519_SPKI_PREFIX.length + X25519_PUBLIC_KEY_LENGTH];
        System.arraycopy(X25519_SPKI_PREFIX, 0, spki, 0, X25519_SPKI_PREFIX.length);
        System.arraycopy(raw, 0, spki, X25519_SPKI_PREFIX.length, X25519_PUBLIC_KEY_LENGTH);

        try {
            return KeyFactory.getInstance("XDH")
                .generatePublic(new X509EncodedKeySpec(spki));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid X25519 public key", e);
        }
    }

    /** Runs X25519 key agreement, yielding the raw 32-byte shared secret. NEVER used as a key directly. */
    public static byte[] computeSharedSecret(PrivateKey privateKey, PublicKey peerPublicKey) {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("XDH");
            agreement.init(privateKey);
            agreement.doPhase(peerPublicKey, true);
            return agreement.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 key agreement failed", e);
        }
    }

    /**
     * Derives the AES-256 UDP key from the raw X25519 shared secret via HKDF-SHA256. The sessionId
     * is intentionally not an argument: the key is a pure function of the ECDH secret, so a party
     * who only ever sees the cleartext sessionId learns nothing about the key.
     */
    public static byte[] deriveKey(byte[] sharedSecret) {
        byte[] prk = hkdfExtract(HKDF_SALT, sharedSecret);
        return hkdfExpand(prk, HKDF_INFO, AES_KEY_LENGTH_BYTES);
    }

    // --- HKDF-SHA256 (RFC 5869), implemented over Mac "HmacSHA256" because the JDK's native HKDF
    // API (JEP 510) only exists on Java 24+ and GTNH targets Java 17-23. ---

    private static byte[] hkdfExtract(byte[] salt, byte[] ikm) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // An all-zero HashLen key is the RFC 5869 default when no salt is supplied; a non-empty
            // salt is used verbatim. Either way the key must be non-empty for Mac.init.
            byte[] keyBytes = salt.length == 0 ? new byte[mac.getMacLength()] : salt;
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            return mac.doFinal(ikm);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));

            int hashLen = mac.getMacLength();
            int blocks = (length + hashLen - 1) / hashLen;
            if (blocks > 255) throw new IllegalArgumentException("HKDF output too long");

            byte[] okm = new byte[length];
            byte[] previous = new byte[0];
            int copied = 0;
            for (int counter = 1; counter <= blocks; counter++) {
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
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    public static String abbreviateSessionId(UUID sessionId) {
        String s = sessionId.toString();
        return s.substring(0, Math.min(8, s.length()));
    }

    public static String fingerprintKey(byte[] key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, key.length); i++) {
            sb.append(String.format("%02x", key[i]));
        }
        return sb.toString();
    }

    private VoiceProtocol() {}
}
