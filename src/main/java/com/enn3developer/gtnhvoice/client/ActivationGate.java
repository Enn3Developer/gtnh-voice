package com.enn3developer.gtnhvoice.client;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.minecraft.client.settings.KeyBinding;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Keyboard;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.api.server.ActivationIds;
import com.enn3developer.gtnhvoice.core.api.util.AudioUtil;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Decides, per captured frame, whether the mic should actually transmit and under which activation id. Registered
 * once at mod init - independent of any particular voice session - so push-to-talk keeps working across
 * reconnects; keyed addon activations deliberately do not: {@link VoiceClientManager} releases them at every session
 * boundary ({@link #releaseActivations()}). {@link CaptureSendWorker} calls {@link #transmitAs(short[])} for every
 * frame it pulls off the capture queue and only encodes+sends when it returns an id, stamping that id on the
 * packet; capture itself is never gated, only transmission.
 * <p>
 * Precedence, per frame: hard mute ({@code CaptureManager.setMuted}) drops it whatever else holds; otherwise a
 * keyed addon activation sends it under the winning activation's id (see {@link KeyedActivations}) regardless of
 * the activation mode - no VA threshold, no PTT key; otherwise the activation mode decides (VA above threshold plus
 * hangover, or the PTT key held) and an open gate sends it as ordinary voice, under {@link ActivationIds#DEFAULT}.
 * <p>
 * Must be public: FML's ASM event bus subscriber scanning throws {@link IllegalAccessError} on
 * non-public listener classes under lwjgl3ify.
 */
public class ActivationGate {

    private static final String CATEGORY = "key.categories.gtnhvoice";
    private static final long LOG_INTERVAL_MILLIS = 5000L;

    private final KeyBinding pushToTalkKey = new KeyBinding("key.gtnhvoice.pushToTalk", Keyboard.KEY_NONE, CATEGORY);
    private final KeyedActivations keyedActivations;
    private final BooleanSupplier muted;

    private volatile boolean pushToTalkKeyDown = false;
    // The id the latest frame went out under, null while not transmitting. Written only by the capture worker.
    private volatile @Nullable UUID transmittingAs;
    private long openUntilMillis = 0L;
    private long lastLogMillis = 0L;

    /**
     * @param keyedActivations the addon activations that open the mic regardless of the activation mode
     * @param muted            the hard-mute state ({@code CaptureManager::isMuted}), which wins over everything
     */
    public ActivationGate(@NotNull KeyedActivations keyedActivations, @NotNull BooleanSupplier muted) {
        this.keyedActivations = Objects.requireNonNull(keyedActivations, "keyedActivations");
        this.muted = Objects.requireNonNull(muted, "muted");
    }

    public void register() {
        ClientRegistry.registerKeyBinding(pushToTalkKey);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    /**
     * Whether the client currently considers itself to be transmitting/speaking: whether the latest captured frame
     * was sent, under any id. Updated by {@link #transmitAs(short[])}, cleared when the capture worker stops; safe
     * to poll from any thread.
     */
    public boolean isSpeaking() {
        return transmittingAs != null;
    }

    /**
     * Whether the latest captured frame went out stamped with {@code activationId} and the mic has not been muted
     * since - a muted capture device stops producing frames, so without the mute check the last decision would sit
     * stale. Safe to poll from any thread.
     */
    boolean isTransmittingAs(@NotNull UUID activationId) {
        return !muted.getAsBoolean() && activationId.equals(transmittingAs);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        pushToTalkKeyDown = pushToTalkKey.getIsKeyPressed();
    }

    /**
     * Called from {@link CaptureSendWorker} for every captured frame: the activation id to send it under, or
     * {@code null} to drop it - see the class doc for the precedence. Must only be called from that single worker
     * thread - the VA hangover timer isn't safe for concurrent callers.
     */
    @Nullable
    UUID transmitAs(short[] frame) {
        Config.ActivationMode mode = Config.getActivationMode();
        long now = System.currentTimeMillis();
        boolean shouldLog = now - lastLogMillis >= LOG_INTERVAL_MILLIS;
        if (shouldLog) lastLogMillis = now;

        // The mode is evaluated even while mute or an addon activation overrides it, so the VA hangover tracks the
        // mic continuously and ordinary voice resumes seamlessly once the override ends.
        boolean modeOpen;
        double levelDb = 0;
        if (mode == Config.ActivationMode.PUSH_TO_TALK) {
            modeOpen = pushToTalkKeyDown;
        } else {
            levelDb = AudioUtil.calculateAudioLevel(frame, 0, frame.length);
            if (levelDb >= Config.vaThresholdDb) {
                openUntilMillis = now + Config.vaHangoverMs;
            }
            modeOpen = now < openUntilMillis;
        }

        UUID decision = decide(modeOpen);

        if (shouldLog) logState(mode, decision, levelDb);
        if (!Objects.equals(decision, transmittingAs)) {
            GtnhVoice.LOG.info(
                "[Voice] activation gate {} (mode={}, activationId={})",
                decision != null ? "OPEN" : "CLOSED",
                mode,
                decision);
        }
        transmittingAs = decision;

        return decision;
    }

    /**
     * Called by the capture worker as it stops (the session ended): no frame is being sent any more, so the gate
     * stops reporting the last one. Same single worker thread as {@link #transmitAs}.
     */
    void captureStopped() {
        transmittingAs = null;
    }

    /**
     * Unkeys every addon activation. Called by {@link VoiceClientManager} on every teardown of the voice link - once
     * the old session's capture worker has stopped and before any new session starts - so a fresh session transmits
     * under an activation only after its addon keys it again. Any thread.
     */
    void releaseActivations() {
        keyedActivations.clear();
    }

    /** Mute beats everything, a keyed addon activation beats the mode, and an open mode sends ordinary voice. */
    private @Nullable UUID decide(boolean modeOpen) {
        if (muted.getAsBoolean()) return null;

        UUID keyed = keyedActivations.winner();
        if (keyed != null) return keyed;

        return modeOpen ? ActivationIds.DEFAULT : null;
    }

    private static void logState(Config.ActivationMode mode, @Nullable UUID decision, double levelDb) {
        if (mode == Config.ActivationMode.PUSH_TO_TALK) {
            GtnhVoice.LOG
                .info("[Voice] activation mode={} transmitting={} activationId={}", mode, decision != null, decision);
        } else {
            GtnhVoice.LOG.info(
                "[Voice] activation mode={} transmitting={} activationId={} levelDb={} thresholdDb={}",
                mode,
                decision != null,
                decision,
                Math.round(levelDb),
                Config.vaThresholdDb);
        }
    }
}
