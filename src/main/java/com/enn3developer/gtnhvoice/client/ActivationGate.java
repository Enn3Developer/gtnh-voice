package com.enn3developer.gtnhvoice.client;

import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.api.util.AudioUtil;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Decides, per captured frame, whether the mic should actually transmit. Registered once at mod
 * init - independent of any particular voice session - so push-to-talk keeps working across
 * reconnects. {@link CaptureSendWorker} calls {@link #shouldTransmit(short[])} for every frame it
 * pulls off the capture queue and only encodes+sends when it returns {@code true}; capture itself
 * is never gated, only transmission.
 * <p>
 * Must be public: FML's ASM event bus subscriber scanning throws {@link IllegalAccessError} on
 * non-public listener classes under lwjgl3ify.
 */
public class ActivationGate {

    private static final String CATEGORY = "key.categories.gtnhvoice";
    private static final long LOG_INTERVAL_MILLIS = 5000L;

    private final KeyBinding pushToTalkKey = new KeyBinding("key.gtnhvoice.pushToTalk", Keyboard.KEY_NONE, CATEGORY);

    private volatile boolean pushToTalkKeyDown = false;
    private volatile boolean speaking = false;
    private long openUntilMillis = 0L;
    private long lastLogMillis = 0L;

    public void register() {
        ClientRegistry.registerKeyBinding(pushToTalkKey);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    /**
     * Whether the client currently considers itself to be transmitting/speaking, per the active
     * activation mode. Updated by {@link #shouldTransmit(short[])}; safe to poll from any thread.
     */
    public boolean isSpeaking() {
        return speaking;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        pushToTalkKeyDown = pushToTalkKey.getIsKeyPressed();
    }

    /**
     * Called from {@link CaptureSendWorker} for every captured frame. Must only be called from that
     * single worker thread - the VA hangover timer isn't safe for concurrent callers.
     */
    boolean shouldTransmit(short[] frame) {
        Config.ActivationMode mode = Config.getActivationMode();
        boolean transmit;
        long now = System.currentTimeMillis();
        boolean shouldLog = now - lastLogMillis >= LOG_INTERVAL_MILLIS;

        if (mode == Config.ActivationMode.PUSH_TO_TALK) {
            transmit = pushToTalkKeyDown;

            if (shouldLog) {
                GtnhVoice.LOG.info("[Voice] activation mode={} transmitting={} keyDown={}", mode, transmit, transmit);
                lastLogMillis = now;
            }
        } else {
            double levelDb = AudioUtil.calculateAudioLevel(frame, 0, frame.length);

            if (levelDb >= Config.vaThresholdDb) {
                openUntilMillis = now + Config.vaHangoverMs;
            }
            transmit = now < openUntilMillis;

            if (shouldLog) {
                GtnhVoice.LOG.info(
                    "[Voice] activation mode={} transmitting={} levelDb={} thresholdDb={}",
                    mode,
                    transmit,
                    Math.round(levelDb),
                    Config.vaThresholdDb);
                lastLogMillis = now;
            }
        }

        if (transmit != speaking) {
            GtnhVoice.LOG.info("[Voice] activation gate {} (mode={})", transmit ? "OPEN" : "CLOSED", mode);
        }
        speaking = transmit;

        return transmit;
    }
}
