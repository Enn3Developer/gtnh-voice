package com.enn3developer.gtnhvoice.client;

import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import com.enn3developer.gtnhvoice.client.capture.CaptureManager;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Registers the microphone-capture toggle keybind and drives {@link CaptureManager} from it. Must be public: FML's
 * ASM event bus subscriber scanning throws {@link IllegalAccessError} on non-public listener classes under
 * lwjgl3ify.
 * <p>
 * Registers on {@code FMLCommonHandler.instance().bus()}, not {@code MinecraftForge.EVENT_BUS}: in this Forge
 * version {@link TickEvent.ClientTickEvent} is posted on the FML bus (see FMLCommonHandler#fireClientTickEvent),
 * so a listener registered on the Forge event bus is never invoked.
 */
public class CaptureKeybindHandler {

    private static final String CATEGORY = "key.categories.gtnhvoice";

    private final KeyBinding toggleCaptureKey = new KeyBinding("key.gtnhvoice.toggleCapture", Keyboard.KEY_V, CATEGORY);
    private final CaptureManager captureManager = new CaptureManager();

    public void register() {
        ClientRegistry.registerKeyBinding(toggleCaptureKey);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    public CaptureManager getCaptureManager() {
        return captureManager;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (toggleCaptureKey.isPressed()) {
            captureManager.toggle();
        }
    }
}
