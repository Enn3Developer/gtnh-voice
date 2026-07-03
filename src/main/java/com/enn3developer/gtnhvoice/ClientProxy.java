package com.enn3developer.gtnhvoice;

import com.enn3developer.gtnhvoice.client.ActivationGate;
import com.enn3developer.gtnhvoice.client.ClientConnectionEventHandler;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.VoiceListenerTickHandler;
import com.enn3developer.gtnhvoice.client.capture.CaptureManager;
import com.enn3developer.gtnhvoice.client.slice.LoopbackSliceKeybindHandler;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    // Lifecycle (start/stop) is owned by VoiceClientManager, tied to the voice session's connection state -
    // no manual toggle keybind. What actually gets transmitted is gated by ActivationGate (VA/PTT).
    private final CaptureManager captureManager = new CaptureManager();
    private final ActivationGate activationGate = new ActivationGate();
    private final LoopbackSliceKeybindHandler loopbackSliceKeybindHandler = new LoopbackSliceKeybindHandler();
    private final ClientConnectionEventHandler clientConnectionEventHandler = new ClientConnectionEventHandler();
    private final VoiceListenerTickHandler voiceListenerTickHandler = new VoiceListenerTickHandler();

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);

        activationGate.register();
        loopbackSliceKeybindHandler.register();
        clientConnectionEventHandler.register();
        voiceListenerTickHandler.register();

        VoiceClientManager.getInstance()
            .bindCaptureManager(captureManager);
        VoiceClientManager.getInstance()
            .bindActivationGate(activationGate);
    }
}
