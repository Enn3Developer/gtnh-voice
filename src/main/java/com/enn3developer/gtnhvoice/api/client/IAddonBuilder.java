package com.enn3developer.gtnhvoice.api.client;

import org.jetbrains.annotations.NotNull;

/**
 * Fluent, single-use assembler for one addon registration, created only by
 * {@link GtnhVoiceClient#addon(String)}. Optionally chain {@link #description(String)}, then terminate with
 * {@link #register()}, which activates the addon and returns its one {@link IVoiceAddon} handle - the gateway
 * every registration bundle and live query goes through.
 * <p>
 * Nothing is registered until {@link #register()}: an abandoned builder leaks nothing and does not claim the
 * name. Single use is enforced: any call after {@code register()}, including a second {@code register()},
 * throws {@link IllegalStateException}.
 */
public interface IAddonBuilder {

    /**
     * Sets the addon's human-readable description, surfaced through {@link IVoiceAddon#description()} (e.g. for
     * a future in-game addon list). A per-addon SCALAR: repeated calls do NOT stack, the LAST call wins. Not
     * calling it leaves the description empty.
     *
     * @param description the addon's description; non-null and non-blank
     * @throws NullPointerException     if {@code description} is null
     * @throws IllegalArgumentException if {@code description} is blank
     */
    IAddonBuilder description(@NotNull String description);

    /**
     * Terminal: registers the addon under its name and returns the durable {@link IVoiceAddon} handle. The
     * builder is dead afterwards - hold on to the handle, not the builder.
     * <p>
     * The name is IDENTITY: it must be unique among registered addons, and there is no unregistration, so a
     * name is claimed for the client lifetime. A duplicate registration is a caller bug and throws.
     *
     * @return the addon's one {@link IVoiceAddon} handle
     * @throws IllegalStateException if an addon with this name is already registered, or if {@code register()}
     *                               already ran on this builder
     */
    IVoiceAddon register();
}
