/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.encryption.aes;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.api.encryption.Encryption;
import com.enn3developer.gtnhvoice.core.api.encryption.EncryptionException;

/**
 * AES-GCM authenticated encryption for the UDP body. GCM (rather than the previous
 * AES/CBC/PKCS5Padding) means every packet is integrity-protected: a tampered or truncated body
 * fails authentication in {@link #decrypt} instead of silently decrypting to garbage, and there is
 * no padding oracle. A fresh random 12-byte nonce is generated per {@link #encrypt} and prepended
 * to the ciphertext, following the same prepend/extract layout the old IV used.
 */
public final class AesEncryption implements Encryption {

    public static final String CIPHER = "AES/GCM/NoPadding";

    // 96-bit nonce is the GCM-recommended size; 128-bit authentication tag is the maximum/standard.
    private static final int NONCE_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private static final ThreadLocal<Cipher> CIPHER_POOL = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(CIPHER);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to create cipher", e);
        }
    });

    private @NotNull SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesEncryption(byte[] keyData) {
        this.key = new SecretKeySpec(keyData, "AES");
    }

    @Override
    public byte[] encrypt(byte[] data) throws EncryptionException {
        try {
            byte[] nonce = generateNonce();

            // initialize cipher. A fresh nonce per call is mandatory for GCM: reusing a (key, nonce)
            // pair is catastrophic, so the nonce is generated here and never reused.
            Cipher cipher = CIPHER_POOL.get();
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));

            // encrypt data (ciphertext includes the appended GCM authentication tag)
            byte[] encrypted = cipher.doFinal(data);

            return copyNonceEncrypted(nonce, encrypted);
        } catch (Exception e) {
            throw new EncryptionException("Failed to encrypt data", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] encrypted) throws EncryptionException {
        try {
            byte[] nonce = nonceFromEncrypted(encrypted);

            // initialize cipher
            Cipher cipher = CIPHER_POOL.get();
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));

            encrypted = dataFromEncrypted(encrypted);
            // doFinal verifies the GCM tag; a mismatch throws (AEADBadTagException), which we surface
            // as an EncryptionException rather than returning unauthenticated plaintext.
            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            throw new EncryptionException("Failed to decrypt data", e);
        }
    }

    @Override
    public void updateKeyData(byte[] keyData) {
        this.key = new SecretKeySpec(keyData, "AES");
    }

    @Override
    public @NotNull SecretKeySpec getKey() {
        return key;
    }

    @Override
    public @NotNull String getName() {
        return CIPHER;
    }

    private byte[] generateNonce() {
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        return nonce;
    }

    /**
     * Copies nonce and encrypted data to a new byte array
     *
     * @return the byte array
     */
    private byte[] copyNonceEncrypted(byte[] nonce, byte[] encrypted) {
        byte[] nonceEncrypted = new byte[nonce.length + encrypted.length];

        System.arraycopy(nonce, 0, nonceEncrypted, 0, nonce.length);
        System.arraycopy(encrypted, 0, nonceEncrypted, nonce.length, encrypted.length);

        return nonceEncrypted;
    }

    private byte[] nonceFromEncrypted(byte[] encrypted) {
        byte[] nonce = new byte[NONCE_LENGTH];
        System.arraycopy(encrypted, 0, nonce, 0, nonce.length);
        return nonce;
    }

    private byte[] dataFromEncrypted(byte[] encrypted) {
        byte[] data = new byte[encrypted.length - NONCE_LENGTH];
        System.arraycopy(encrypted, NONCE_LENGTH, data, 0, data.length);
        return data;
    }
}
