package com.enn3developer.gtnhvoice;

import com.enn3developer.gtnhvoice.network.NetworkHandler;
import com.enn3developer.gtnhvoice.server.VoiceServerManager;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());

        GtnhVoice.LOG.info("I am GTNH Voice at version " + Tags.VERSION);

        NetworkHandler.init();
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent ignoredEvent) {}

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent ignoredEvent) {}

    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent ignoredEvent) {
        VoiceServerManager.getInstance()
            .start();
    }

    public void serverStopping(FMLServerStoppingEvent ignoredEvent) {
        VoiceServerManager.getInstance()
            .stop();
    }
}
