package com.enn3developer.gtnhvoice;

import com.enn3developer.gtnhvoice.client.ActivationGate;
import com.enn3developer.gtnhvoice.client.ClientConnectionEventHandler;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.VoiceListenerTickHandler;
import com.enn3developer.gtnhvoice.client.capture.CaptureManager;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    private final CaptureManager captureManager = new CaptureManager();
    private final ActivationGate activationGate = new ActivationGate();
    private final ClientConnectionEventHandler clientConnectionEventHandler = new ClientConnectionEventHandler();
    private final VoiceListenerTickHandler voiceListenerTickHandler = new VoiceListenerTickHandler();

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);

        activationGate.register();
        clientConnectionEventHandler.register();
        voiceListenerTickHandler.register();

        VoiceClientManager.getInstance()
            .bindCaptureManager(captureManager);
        VoiceClientManager.getInstance()
            .bindActivationGate(activationGate);
    }
}
