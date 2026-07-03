package com.enn3developer.gtnhvoice;

import com.enn3developer.gtnhvoice.client.CaptureKeybindHandler;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    private final CaptureKeybindHandler captureKeybindHandler = new CaptureKeybindHandler();

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);

        captureKeybindHandler.register();
    }
}
