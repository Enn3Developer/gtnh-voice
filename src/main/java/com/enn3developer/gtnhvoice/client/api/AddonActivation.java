package com.enn3developer.gtnhvoice.client.api;

import java.util.UUID;

import com.enn3developer.gtnhvoice.api.client.IAddonActivation;
import com.enn3developer.gtnhvoice.client.KeyedActivations;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;

/**
 * The one {@code IAddonActivation} implementation: keying writes this activation's id into the backend's
 * {@link KeyedActivations}, which the {@code ActivationGate} reads per captured frame; {@code isTransmitting}
 * asks the live gate through {@link VoiceClientManager}, where no bound gate IS the documented no-session case.
 * Holds no state of its own, so it is as thread-safe as the registry behind it.
 */
final class AddonActivation implements IAddonActivation {

    private final UUID id;
    private final KeyedActivations keyedActivations;

    AddonActivation(UUID id, KeyedActivations keyedActivations) {
        this.id = id;
        this.keyedActivations = keyedActivations;
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public void setKeyed(boolean keyed) {
        if (keyed) {
            keyedActivations.key(id);
        } else {
            keyedActivations.unkey(id);
        }
    }

    @Override
    public boolean isKeyed() {
        return keyedActivations.isKeyed(id);
    }

    @Override
    public boolean isTransmitting() {
        // isKeyed first: an unkeyed activation stops reporting at once instead of a frame later.
        return isKeyed() && VoiceClientManager.getInstance()
            .isTransmittingAs(id);
    }
}
