package com.enn3developer.gtnhvoice.client;

import net.minecraft.client.settings.KeyBinding;

import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.capture.CaptureManager;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Dedicated toggle keybind for the hard self-mute (see {@link CaptureManager#setMuted(boolean)}): unlike {@link
 * ActivationGate}'s push-to-talk key, this is a one-shot toggle, so it uses {@link KeyBinding#isPressed()} (consumes
 * the press, fires once per key-down) rather than the continuous {@link KeyBinding#getIsKeyPressed()} PTT uses.
 * Defaults to {@code M}, rebindable via Controls like any other key.
 * <p>
 * Must be public: FML's ASM event bus subscriber scanning throws {@link IllegalAccessError} on non-public listener
 * classes under lwjgl3ify.
 */
public class MicMuteKeyHandler {

    private static final String CATEGORY = "key.categories.gtnhvoice";

    private final KeyBinding muteKey = new KeyBinding("key.gtnhvoice.microphoneMute", Keyboard.KEY_M, CATEGORY);

    private volatile CaptureManager captureManager;

    public void register() {
        ClientRegistry.registerKeyBinding(muteKey);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    /**
     * Wires the shared {@link CaptureManager} in - bound once at mod init, independent of any particular voice
     * session, so the mute toggle keeps working across reconnects.
     */
    public void bindCaptureManager(@NotNull CaptureManager captureManager) {
        this.captureManager = captureManager;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (muteKey.isPressed()) {
            toggleMute();
        }
    }

    private void toggleMute() {
        CaptureManager manager = captureManager;
        if (manager == null) return;

        boolean newMuted = !manager.isMuted();
        manager.setMuted(newMuted);
        GtnhVoice.LOG.info("[Voice] Microphone {} via keybind", newMuted ? "muted" : "unmuted");
    }
}
