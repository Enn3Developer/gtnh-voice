package com.enn3developer.gtnhvoice.api.client;

import org.jetbrains.annotations.NotNull;

/**
 * The capture-side addon API: durable PCM filter registration bundles on outgoing mic audio. Obtain the
 * instance via {@link GtnhVoiceClientApi#capture()}.
 */
public interface IClientCaptureApi {

    /**
     * Opens a fluent registration bundle attributed to {@code addonName} - the EXCLUSIVE way to hook the
     * capture path: chain capture PCM filters on the returned builder, then call {@code done()} to activate the
     * bundle and receive the {@link IRegistration} handle that removes it as a whole.
     * <p>
     * Registrations are DURABLE: register once (e.g. at FML init) and the bundle survives voice session
     * disconnects and reconnects without any re-registration. Registering mid-session is fully supported and
     * takes effect from the next captured mic frame.
     * <p>
     * {@code addonName} is attribution, not identity: it names the addon in error logs (and future per-addon
     * throttling), and multiple bundles may share one name.
     *
     * @param addonName the registering addon's name, for attribution; non-null and non-blank
     * @return a single-use builder - see {@link ICaptureRegistrationBuilder} for the chaining rules
     * @throws NullPointerException     if {@code addonName} is null
     * @throws IllegalArgumentException if {@code addonName} is blank
     */
    ICaptureRegistrationBuilder register(@NotNull String addonName);
}
