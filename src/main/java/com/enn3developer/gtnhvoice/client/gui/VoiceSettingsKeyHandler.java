package com.enn3developer.gtnhvoice.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Opens {@link GuiVoiceSettings} on a dedicated keybind, defaulting to comma ({@code ,}) - unused by vanilla,
 * rebindable via Controls like any other key. Only opens over the in-game HUD, not over another already-open
 * screen, mirroring how vanilla's own GUI-opening keys behave.
 * <p>
 * Must be public: FML's ASM event bus subscriber scanning throws {@link IllegalAccessError} on non-public listener
 * classes under lwjgl3ify.
 */
public class VoiceSettingsKeyHandler {

    private static final String CATEGORY = "key.categories.gtnhvoice";

    private final KeyBinding openSettingsKey = new KeyBinding(
        "key.gtnhvoice.openSettings",
        Keyboard.KEY_COMMA,
        CATEGORY);

    private boolean keyWasDown;

    public void register() {
        ClientRegistry.registerKeyBinding(openSettingsKey);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        boolean keyDown = openSettingsKey.getIsKeyPressed();
        if (keyDown && !keyWasDown) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.currentScreen == null) {
                mc.displayGuiScreen(new GuiVoiceSettings());
            }
        }
        keyWasDown = keyDown;
    }
}
