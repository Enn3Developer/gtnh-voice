package com.enn3developer.gtnhvoice;

import com.enn3developer.gtnhvoice.client.CaptureKeybindHandler;
import com.enn3developer.gtnhvoice.client.ClientConnectionEventHandler;
import com.enn3developer.gtnhvoice.client.slice.LoopbackSliceKeybindHandler;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    private final CaptureKeybindHandler captureKeybindHandler = new CaptureKeybindHandler();
    private final LoopbackSliceKeybindHandler loopbackSliceKeybindHandler = new LoopbackSliceKeybindHandler();
    private final ClientConnectionEventHandler clientConnectionEventHandler = new ClientConnectionEventHandler();

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);

        captureKeybindHandler.register();
        loopbackSliceKeybindHandler.register();
        clientConnectionEventHandler.register();
    }
}
