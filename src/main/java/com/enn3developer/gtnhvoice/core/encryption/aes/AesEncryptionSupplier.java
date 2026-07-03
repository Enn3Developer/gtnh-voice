/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.encryption.aes;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.api.encryption.Encryption;
import com.enn3developer.gtnhvoice.core.api.encryption.EncryptionSupplier;

public final class AesEncryptionSupplier implements EncryptionSupplier {

    @Override
    public @NotNull Encryption create(byte[] data) {
        return new AesEncryption(data);
    }

    @Override
    public @NotNull String getName() {
        return AesEncryption.CIPHER;
    }
}
