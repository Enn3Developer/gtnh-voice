package com.enn3developer.gtnhvoice.api.client;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.client.api.ClientApiBackend;

/**
 * Static entry point addons use to reach the voice client - the single place this package touches the client
 * internals, so addons never import them. Registering the addon is the only door in: {@link #addon(String)}
 * opens the one-shot {@link IAddonBuilder}, whose {@code register()} returns the {@link IVoiceAddon} handle
 * everything else hangs off. Client side only, like everything in this package - it exists on clients
 * (including the client half of an integrated server), never on a dedicated server.
 *
 * <pre>{@code
 * IVoiceAddon addon = GtnhVoiceClient.addon("MyAddon")
 *     .description("This is my addon")
 *     .register();
 * IRegistration reg = addon.audio()
 *     .playbackChain(chain -> ...)
 *     .done();
 * }</pre>
 */
public final class GtnhVoiceClient {

    private GtnhVoiceClient() {}

    /**
     * Opens a fluent, single-use registration builder for the addon named {@code name}. The name is IDENTITY,
     * not mere attribution: it appears in error logs, must be unique among registered addons, and is claimed
     * when the builder's {@link IAddonBuilder#register()} runs - never by this call alone.
     *
     * @param name the addon's unique name; non-null and non-blank
     * @return a single-use builder - see {@link IAddonBuilder}
     * @throws NullPointerException     if {@code name} is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public static IAddonBuilder addon(@NotNull String name) {
        return ClientApiBackend.getInstance()
            .newAddonBuilder(name);
    }
}
