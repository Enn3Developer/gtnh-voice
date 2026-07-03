package com.enn3developer.gtnhvoice;

import com.enn3developer.gtnhvoice.client.CaptureKeybindHandler;
import com.enn3developer.gtnhvoice.client.ClientConnectionEventHandler;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.VoiceListenerTickHandler;
import com.enn3developer.gtnhvoice.client.slice.LoopbackSliceKeybindHandler;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    private final CaptureKeybindHandler captureKeybindHandler = new CaptureKeybindHandler();
    private final LoopbackSliceKeybindHandler loopbackSliceKeybindHandler = new LoopbackSliceKeybindHandler();
    private final ClientConnectionEventHandler clientConnectionEventHandler = new ClientConnectionEventHandler();
    private final VoiceListenerTickHandler voiceListenerTickHandler = new VoiceListenerTickHandler();

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);

        captureKeybindHandler.register();
        loopbackSliceKeybindHandler.register();
        clientConnectionEventHandler.register();
        voiceListenerTickHandler.register();

        VoiceClientManager.getInstance()
            .bindCaptureManager(captureKeybindHandler.getCaptureManager());
    }
}
