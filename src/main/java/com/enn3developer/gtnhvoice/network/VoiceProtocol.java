package com.enn3developer.gtnhvoice.network;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

import com.enn3developer.gtnhvoice.core.proto.packets.PacketUtil;

/**
 * Shared constants and helpers for the reliable control-channel handshake. Referenced from both
 * {@code server} and {@code client} packages so the two sides can never drift apart on how the UDP
 * encryption key is derived from the session secret.
 */
public final class VoiceProtocol {

    public static final String CHANNEL = "gtnhvoice";
    // v2: SourceAudioPacket now carries the speaker's absolute position (x,y,z) instead of a
    // scalar distance, so a mismatched client/server pair must fail the handshake cleanly rather
    // than misreading UDP audio packet bytes.
    // v3: adds VoiceGroupUpdatePacket, always sent on the FML control channel; an old client would
    // fail in the codec on the unknown discriminator, so the handshake must turn the skew into a
    // clean reject + chat message instead.
    public static final byte PROTOCOL_VERSION = 3;

    public static final byte REASON_VERSION_MISMATCH = 0;

    private static final int AES_KEY_LENGTH_BYTES = 16;

    /**
     * Derives the AES key for a session's UDP traffic as the first 16 bytes of SHA-256(secret), so
     * neither side ever transmits key material - only the secret itself travels over the reliable
     * channel.
     */
    public static byte[] deriveKey(UUID secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(PacketUtil.getUUIDBytes(secret));
            return Arrays.copyOf(hash, AES_KEY_LENGTH_BYTES);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm; this can never happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String abbreviateSecret(UUID secret) {
        String s = secret.toString();
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
