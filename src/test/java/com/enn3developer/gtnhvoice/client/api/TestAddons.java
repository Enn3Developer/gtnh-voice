package com.enn3developer.gtnhvoice.client.api;

import java.util.HashMap;
import java.util.Map;

import com.enn3developer.gtnhvoice.api.client.IAudioRegistrationBuilder;
import com.enn3developer.gtnhvoice.api.client.ICaptureRegistrationBuilder;
import com.enn3developer.gtnhvoice.api.client.IVoiceAddon;

/**
 * Test convenience mirroring how a real addon uses the API: register each name once, cache the handle, and open
 * any number of bundles off it - so a test that builds several bundles under one name doesn't trip the addon
 * name-uniqueness rule.
 */
final class TestAddons {

    private final ClientApiBackend backend;
    private final Map<String, IVoiceAddon> addons = new HashMap<>();

    TestAddons(ClientApiBackend backend) {
        this.backend = backend;
    }

    IVoiceAddon addon(String name) {
        return addons.computeIfAbsent(
            name,
            n -> backend.newAddonBuilder(n)
                .register());
    }

    IAudioRegistrationBuilder audio(String name) {
        return addon(name).audio();
    }

    ICaptureRegistrationBuilder capture(String name) {
        return addon(name).capture();
    }
}
