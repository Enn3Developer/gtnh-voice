package com.enn3developer.gtnhvoice.client.api;

import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.api.client.IAddonBuilder;
import com.enn3developer.gtnhvoice.api.client.IVoiceAddon;

/**
 * The one {@code IAddonBuilder} implementation: accumulates the optional description, then hands the whole
 * registration to {@link ClientApiBackend#registerAddon}, which owns the name-uniqueness check - so an
 * abandoned builder never claims its name. Not thread-safe within one instance, like the registration-bundle
 * builders: a builder is a local, single-threaded assembly convenience.
 */
final class AddonBuilder implements IAddonBuilder {

    private final ClientApiBackend backend;
    private final String name;
    private @Nullable String description;
    private boolean registered;

    AddonBuilder(ClientApiBackend backend, String name) {
        this.backend = backend;
        this.name = name;
    }

    @Override
    public IAddonBuilder description(@NotNull String description) {
        ensureNotRegistered();
        Objects.requireNonNull(description, "description");
        if (description.trim()
            .isEmpty()) throw new IllegalArgumentException("description must not be blank");
        this.description = description;
        return this;
    }

    @Override
    public IVoiceAddon register() {
        ensureNotRegistered();
        registered = true;
        return backend.registerAddon(name, description);
    }

    private void ensureNotRegistered() {
        if (registered) throw new IllegalStateException("register() already ran; this builder is single-use");
    }
}
