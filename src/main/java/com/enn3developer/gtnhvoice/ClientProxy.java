package com.enn3developer.gtnhvoice;

import com.enn3developer.gtnhvoice.client.ActivationGate;
import com.enn3developer.gtnhvoice.client.ClientConnectionEventHandler;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.VoiceListenerTickHandler;
import com.enn3developer.gtnhvoice.client.audio.AudioDeviceController;
import com.enn3developer.gtnhvoice.client.capture.CaptureManager;
import com.enn3developer.gtnhvoice.client.gui.VoiceSettingsKeyHandler;
import com.enn3developer.gtnhvoice.client.hud.VoiceHudRenderer;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    private final CaptureManager captureManager = new CaptureManager();
    private final ActivationGate activationGate = new ActivationGate();
    private final ClientConnectionEventHandler clientConnectionEventHandler = new ClientConnectionEventHandler();
    private final VoiceListenerTickHandler voiceListenerTickHandler = new VoiceListenerTickHandler();
    private final VoiceHudRenderer voiceHudRenderer = new VoiceHudRenderer();
    private final AudioDeviceController audioDeviceController = AudioDeviceController.getInstance();
    private final VoiceSettingsKeyHandler voiceSettingsKeyHandler = new VoiceSettingsKeyHandler();

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);

        activationGate.register();
        clientConnectionEventHandler.register();
        voiceListenerTickHandler.register();
        voiceHudRenderer.register();
        voiceSettingsKeyHandler.register();

        VoiceClientManager.getInstance()
            .bindCaptureManager(captureManager);
        VoiceClientManager.getInstance()
            .bindActivationGate(activationGate);

        // super.preInit() above already ran Config.synchronizeConfiguration, so this is the persisted value, not
        // the compile-time default - captureManager is constructed before preInit fires (FML processes @SidedProxy
        // during the construction phase), so it can't safely read Config itself at field-init time.
        audioDeviceController.bindCaptureManager(captureManager);
        captureManager.setInputDevice(Config.getInputDeviceOrNull());
    }
}
