/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.encryption;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.api.encryption.Encryption;
import com.enn3developer.gtnhvoice.core.api.encryption.EncryptionManager;
import com.enn3developer.gtnhvoice.core.api.encryption.EncryptionSupplier;
import com.google.common.collect.Maps;

public final class VoiceEncryptionManager implements EncryptionManager {

    private final Map<String, EncryptionSupplier> algorithms = Maps.newHashMap();

    @Override
    public synchronized @NotNull Encryption create(@NotNull String name, byte[] data) {
        checkNotNull(name, "name cannot be null");
        checkNotNull(data, "params cannot be null");

        EncryptionSupplier supplier = algorithms.get(name);
        if (supplier == null) {
            throw new IllegalArgumentException("Encryption algorithm with name " + name + " is not registered");
        }

        return supplier.create(data);
    }

    @Override
    public synchronized void register(@NotNull EncryptionSupplier supplier) {
        String name = supplier.getName();

        checkNotNull(name, "name cannot be null");
        checkNotNull(supplier, "supplier cannot be null");

        if (algorithms.containsKey(name)) {
            throw new IllegalArgumentException("Encryption algorithm with name " + name + " is already exist");
        }

        algorithms.put(name, supplier);
    }

    @Override
    public synchronized boolean unregister(@NotNull String name) {
        checkNotNull(name, "name cannot be null");
        return algorithms.remove(name) != null;
    }

    @Override
    public synchronized boolean unregister(@NotNull EncryptionSupplier supplier) {
        String name = supplier.getName();
        checkNotNull(name, "name cannot be null");
        return algorithms.remove(name) != null;
    }

    @Override
    public synchronized Collection<EncryptionSupplier> getAlgorithms() {
        return algorithms.values();
    }
}
