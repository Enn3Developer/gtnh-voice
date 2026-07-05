package com.enn3developer.gtnhvoice;

import com.enn3developer.gtnhvoice.client.ActivationGate;
import com.enn3developer.gtnhvoice.client.ClientConnectionEventHandler;
import com.enn3developer.gtnhvoice.client.MicMuteKeyHandler;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.VoiceListenerTickHandler;
import com.enn3developer.gtnhvoice.client.api.ClientApiBackend;
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
    private final MicMuteKeyHandler micMuteKeyHandler = new MicMuteKeyHandler();

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);

        activationGate.register();
        clientConnectionEventHandler.register();
        voiceListenerTickHandler.register();
        voiceHudRenderer.register();
        voiceSettingsKeyHandler.register();
        micMuteKeyHandler.register();

        VoiceClientManager.getInstance()
            .bindCaptureManager(captureManager);
        VoiceClientManager.getInstance()
            .bindActivationGate(activationGate);
        micMuteKeyHandler.bindCaptureManager(captureManager);

        // Before the first session can ever start: sessionStarted is what wires stored addon-API bundles onto
        // each fresh per-session PlaybackManager, so the bridge's session listener must already be registered.
        ClientApiBackend.getInstance()
            .initSessionBridging();

        // super.preInit() above already ran Config.synchronizeConfiguration, so this is the persisted value, not
        // the compile-time default - captureManager is constructed before preInit fires (FML processes @SidedProxy
        // during the construction phase), so it can't safely read Config itself at field-init time.
        audioDeviceController.bindCaptureManager(captureManager);
        captureManager.setInputDevice(Config.getInputDeviceOrNull());
    }
}
