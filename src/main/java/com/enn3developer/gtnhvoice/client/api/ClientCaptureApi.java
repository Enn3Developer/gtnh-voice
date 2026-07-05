package com.enn3developer.gtnhvoice.client.api;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.api.client.ICaptureRegistrationBuilder;
import com.enn3developer.gtnhvoice.api.client.IClientCaptureApi;

/**
 * The one {@code IClientCaptureApi} implementation: registration hands out builders that store into
 * {@link ClientApiBackend}.
 */
final class ClientCaptureApi implements IClientCaptureApi {

    private final ClientApiBackend backend;

    ClientCaptureApi(ClientApiBackend backend) {
        this.backend = backend;
    }

    @Override
    public ICaptureRegistrationBuilder register(@NotNull String addonName) {
        return new CaptureRegistrationBuilder(backend, ClientApiBackend.validateAddonName(addonName));
    }
}
