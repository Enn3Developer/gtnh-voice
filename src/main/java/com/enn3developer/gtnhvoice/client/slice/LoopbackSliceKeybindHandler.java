package com.enn3developer.gtnhvoice.client.slice;

import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * DEV/VALIDATION HARNESS keybind: toggles the full mic -&gt; encode -&gt; UDP -&gt; decode -&gt; playback loopback
 * slice
 * ({@link VoiceLoopbackSlice}). Must be public: FML's ASM event bus subscriber scanning throws
 * {@link IllegalAccessError} on non-public listener classes under lwjgl3ify.
 */
public class LoopbackSliceKeybindHandler {

    private static final String CATEGORY = "key.categories.gtnhvoice";

    private final KeyBinding toggleSliceKey = new KeyBinding(
        "key.gtnhvoice.toggleLoopbackSlice",
        Keyboard.KEY_B,
        CATEGORY);
    private final VoiceLoopbackSlice slice = new VoiceLoopbackSlice();

    public void register() {
        ClientRegistry.registerKeyBinding(toggleSliceKey);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (toggleSliceKey.isPressed()) {
            slice.toggle();
        }
    }
}
