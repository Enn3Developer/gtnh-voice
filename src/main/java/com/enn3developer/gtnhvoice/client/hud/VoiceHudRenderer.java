package com.enn3developer.gtnhvoice.client.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.animation.Animator;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IInterpolation;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Interpolation;
import com.cleanroommc.modularui.utils.Platform;
import com.cleanroommc.modularui.widget.Widget;
import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.PlayerVoiceSettings;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.VoiceClientSession;
import com.enn3developer.gtnhvoice.client.VoiceSkinIcons;
import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;
import com.enn3developer.gtnhvoice.client.source.VoiceSourceManager;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * The "who's talking" HUD: a compact bottom-left list anchored on a pinned self row in the corner (always
 * visible while connected, its dot showing mic state: muted/speaking/idle), with every muted/speaking player
 * stacking upward above it. Built as a read-only MUI2 {@link ModularScreen} rendered through {@link ModularHud},
 * with row slide/fade driven by MUI2 {@link Animator}s ticked by {@link HudAnimations} (never by MUI2's own
 * AnimatorManager, which only runs while a GUI screen is open). Purely a read/render layer - never mutates
 * voice state.
 */
@Lwjgl3Aware
public class VoiceHudRenderer {

    private static final int DOT_SIZE = 6;
    private static final int DOT_TEXT_GAP = 4;
    private static final int HEAD_SIZE = 8;
    private static final int HEAD_TEXT_GAP = 3;
    private static final int LINE_HEIGHT = 10;
    private static final int MARGIN = 2;
    private static final int SPEAKING_DOT_COLOR = 0xFF55FF55;
    private static final int MUTED_DOT_COLOR = 0xFFFF5555;
    private static final int IDLE_DOT_COLOR = 0xFF888888;
    private static final int TEXT_RGB = 0xFFFFFF;
    private static final int SLIDE_DURATION_MS = 250;
    private static final int FADE_DURATION_MS = 150;

    // Row entrance/exit theatrics: rows slide in from the left with a BACK_OUT overshoot while fading in,
    // and leave with a BACK_IN anticipation (a nudge right, then out left) while fading out. The fade ends
    // before the entrance settle so the overshoot plays at full opacity.
    private static final int ROW_ENTER_MS = 350;
    private static final int ROW_ENTER_FADE_MS = 250;
    private static final int ROW_EXIT_MS = 300;
    private static final float ROW_SLIDE_X_PX = 40.0F;
    // Reorder arc: rows bow this many px to the RIGHT (into the screen - left would clip at the edge) while
    // sliding to a new slot, peaking mid-travel, so vertical moves read as arcs instead of elevator rides.
    private static final float ROW_REORDER_ARC_PX = 5.0F;

    // Motion transparency: a row in positional motion dims to this alpha (eased with the tau below), faking
    // motion blur - the eye forgives whole-pixel position stepping far more when the mover is translucent.
    // Rows return to full opacity at rest, so stationary text stays crisp.
    private static final float ROW_MOTION_ALPHA = 0.85F;
    private static final float MOTION_DIM_TAU_MS = 60.0F;
    // How long a speaker's row lingers (holding its slot, meter draining to silence) after their speech
    // segment ends, before the removal fade starts. Purely presentational - the audio-side inactivity
    // timeout is untouched.
    private static final long SPEAKING_LINGER_MS = 5_000L;

    // Whole-HUD fade targets, driven by one animator (see SpeakerListWidget#updateHudFade). Screen open wins:
    // fade to fully transparent. Otherwise a fresh chat line (vanilla shows it 200 ticks = 10s, bottom-left
    // like us) dims to CHAT_DIM_ALPHA - dim, not hidden, so mic state stays glanceable while chat is readable.
    private static final float SCREEN_HIDDEN_ALPHA = 0.0F;
    private static final float CHAT_DIM_ALPHA = 0.3F;
    private static final long CHAT_DIM_HOLD_MS = 10_000L;
    private static final int HUD_FADE_MS = 250;

    // Self-row indicator animations: state-color crossfade duration, the speaking alpha-pulse period/floor,
    // and the mic-level meter's per-frame smoothing factors (fast attack, slow release, classic meter feel).
    private static final int SELF_COLOR_FADE_MS = 150;
    private static final int SELF_SHAPE_MORPH_MS = 200;
    private static final int SELF_UNMUTE_MORPH_MS = 550;
    private static final int BAR_GAP = 3;
    // Indicator column width for every row: the self meter's fully spread bars (3 bars + 2 gaps). Dots and
    // the muted square are centered inside it, so heads and labels stay column-aligned across all rows.
    private static final int INDICATOR_WIDTH = (DOT_SIZE / 3) * 3 + BAR_GAP * 2;
    private static final long PULSE_PERIOD_MS = 900L;
    private static final float PULSE_MIN_ALPHA = 0.75F;
    // Meter smoothing time constants (exponential easing, framerate-independent): deliberately gentle on
    // both edges so the meter reads as a clean swell rather than snappy flicker.
    private static final float LEVEL_ATTACK_TAU_MS = 90.0F;
    private static final float LEVEL_RELEASE_TAU_MS = 220.0F;

    // Written by the chat event handler (client thread), read every draw frame; volatile out of caution.
    private static volatile long lastChatAtMillis = Long.MIN_VALUE / 2;

    // How much wider (px, total) the solid square gets at the struggle's widen peaks.
    private static final float SELF_WIDEN_PX = 3.0F;

    /**
     * The unmute struggle's squareness track, over normalized progress: HELD at the starting squareness for
     * the whole struggle act (the square stays solid - the struggle is expressed by the widen track below),
     * then smoothsteps down past zero to a -0.25 overshoot (the pry+snap: gaps rip open beyond their resting
     * spread), then settles back to rest with a damped wobble; both settle factors hit zero exactly at p=1.
     */
    private static float unmuteSquareness(float p, float start) {
        if (p < 0.35F) return start;
        if (p < 0.65F) {
            float t = (p - 0.35F) / 0.3F;
            float smooth = t * t * (3 - 2 * t);
            return start + (-0.25F - start) * smooth;
        }
        float t = (p - 0.65F) / 0.35F;
        return -0.25F * (1 - t) * (float) Math.cos(t * 2.5F * Math.PI);
    }

    /**
     * The unmute struggle's height track: the bars keep the FULL square height through the struggle and the
     * whole pry/snap - full-height columns rip apart - and only once snapped open (the settle act) do the
     * heights ease down to the live level-driven meter.
     */
    private static float unmuteHeightSquareness(float p, float start) {
        if (p < 0.65F) return start;
        float t = (p - 0.65F) / 0.35F;
        float smooth = t * t * (3 - 2 * t);
        return start * (1 - smooth);
    }

    /**
     * The unmute struggle's widen track: the solid square widens, returns to normal, then widens AGAIN and
     * holds - and from that second widened stance the squareness track pries it apart into the snap, during
     * which the extra width drains away. Zero from the snap's end onward.
     */
    private static float unmuteWiden(float p) {
        if (p < 0.35F) {
            float t = p / 0.35F;
            // First hump: widen and fall back. Second act: widen again, ending at full stretch.
            if (t < 0.5F) return (float) Math.sin(Math.PI * (t / 0.5F));
            return (float) Math.sin(Math.PI / 2 * ((t - 0.5F) / 0.5F));
        }
        if (p < 0.65F) return 1.0F - (p - 0.35F) / 0.3F;
        return 0.0F;
    }

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        new ModularHud(createScreen()).register();
    }

    /**
     * Records when the last chat line arrived; the widget's draw pass derives the dim state from this
     * timestamp (never from here - animator (un)registration is draw-pass-only, see {@link AnimatedRow}).
     */
    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        lastChatAtMillis = Minecraft.getSystemTime();
    }

    private static ModularScreen createScreen() {
        ModularPanel panel = ModularPanel.defaultPanel("voice_hud")
            .full()
            .background(IDrawable.EMPTY)
            .child(new SpeakerListWidget().full());
        return new ModularScreen(GtnhVoice.MODID, panel);
    }

    /**
     * Full-screen read-only widget that reconciles the live speaker/muted lists into animated rows each frame
     * and draws them bottom-up from the corner. Rows slide in from the left with an overshoot while fading in,
     * slide ({@link Interpolation#CUBIC_OUT}) when their slot shifts, and leave with an anticipation nudge
     * before sliding out left and fading; the pinned self row is drawn directly every frame and never enters
     * or leaves.
     */
    public static class SpeakerListWidget extends Widget<SpeakerListWidget> {

        private final LinkedHashMap<UUID, AnimatedRow> rows = new LinkedHashMap<>();

        // When each source's speech segment was last seen active, for the post-speech linger: a row keeps its
        // slot for SPEAKING_LINGER_MS after the segment ends. Draw-pass only, like the rows map.
        private final Map<UUID, Long> lastSpeakingAtMillis = new HashMap<>();

        // Whole-HUD alpha multiplier (screen-open hide + chat-yield dim); every row (self included) scales
        // by it. hudTargetAlpha is the fade's current destination, used to retarget only on state changes.
        private float hudAlpha = 1.0F;
        private float hudTargetAlpha = 1.0F;
        private Animator hudFade;

        // Self-row indicator state: crossfading dot color (from -> to at colorT), and the smoothed mic level
        // driving the three meter bars. All mutated in the draw pass / animator callbacks only.
        private int selfColorFrom = IDLE_DOT_COLOR;
        private int selfColorTo = IDLE_DOT_COLOR;
        private float selfColorT = 1.0F;
        private Animator selfColorAnim;
        private float smoothedMicLevel;
        private long lastLevelFrameMillis;

        // Muted morph: 0 = live meter bars, 1 = the solid square every other row shows. The bars sit flush,
        // so all three at full height IS the square - the morph just animates the heights. selfWiden is the
        // unmute struggle's extra square width (0..1 of SELF_WIDEN_PX), only ever non-zero mid-unmute.
        private float selfSquareness;
        // Height channel of the morph: tracks selfSquareness while muting, but on unmute it holds full height
        // through the pry/snap and only drops during the settle (see unmuteHeightSquareness).
        private float selfHeightSquareness;
        private float selfSquarenessTarget;
        private float selfWiden;
        private Animator selfShapeAnim;

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
            Minecraft mc = Minecraft.getMinecraft();
            VoiceClientManager clientManager = VoiceClientManager.getInstance();

            // A tall F3 debug left column can reach down into the bottom-left corner - keep yielding to it.
            boolean visible = Config.hudEnabled && clientManager.getSession()
                .getState() == VoiceClientSession.State.CONNECTED;
            if (!visible) {
                // Drop all animation state so a re-appearing HUD starts clean instead of animating stale rows.
                clearRows();
                return;
            }

            int selfY = getArea().h() - MARGIN - LINE_HEIGHT;
            long now = Minecraft.getSystemTime();

            updateHudFade(mc);
            reconcile(clientManager, selfY, now);
            prune();

            // Framerate-independent exponential smoothing: the same swell at 60 or 144 fps. dt is clamped so
            // a hitch (or the first frame after a pause) can't teleport the meters.
            float dt = lastLevelFrameMillis == 0 ? 16.0F : Math.min(100L, now - lastLevelFrameMillis);
            lastLevelFrameMillis = now;

            // One shared breathing phase - speaking rows (self included) pulse in sync.
            float speakPulse = PULSE_MIN_ALPHA + (1.0F - PULSE_MIN_ALPHA)
                * (0.5F + 0.5F * (float) Math.sin(now * (Math.PI * 2 / PULSE_PERIOD_MS)));

            // Speaking rows render as live per-speaker meters (decoded-audio level) with the breathing pulse;
            // muted rows keep the steady square.
            VoiceSourceManager sourceManager = clientManager.getVoiceSourceManager();
            PlaybackManager playback = sourceManager == null ? null : sourceManager.getPlaybackManager();
            for (AnimatedRow row : rows.values()) {
                int rowX = MARGIN + Math.round(row.xOffset + row.slideBow);
                // Motion-blur fake: translucent while traveling, crisp at rest (see ROW_MOTION_ALPHA).
                float dimTarget = row.isMoving() ? ROW_MOTION_ALPHA : 1.0F;
                row.motionDim += (dimTarget - row.motionDim) * (1.0F - (float) Math.exp(-dt / MOTION_DIM_TAU_MS));
                float rowAlpha = row.alpha * hudAlpha * row.motionDim;

                if (row.dotColor == SPEAKING_DOT_COLOR) {
                    float rowLevel = playback == null ? 0.0F : playback.sourceLevel(row.uuid);
                    row.smoothedLevel = smoothLevel(row.smoothedLevel, rowLevel, dt);
                    drawMeterRow(
                        mc,
                        row.uuid,
                        row.label,
                        rowX,
                        Math.round(row.y),
                        row.dotColor,
                        rowAlpha,
                        speakPulse,
                        row.smoothedLevel,
                        0.0F,
                        0.0F,
                        0.0F);
                } else {
                    drawRow(mc, row.uuid, row.label, rowX, Math.round(row.y), row.dotColor, rowAlpha);
                }
            }

            // Pinned self row, drawn last so it stays on top while neighbors animate through. Its indicator is
            // a three-bar mic meter (live capture level) whose color crossfades with mic state
            // (muted/speaking/idle) and which pulses gently while speaking.
            boolean selfMuted = clientManager.isMuted();
            boolean selfSpeaking = !selfMuted && clientManager.isSpeaking();
            EntityPlayer self = mc.thePlayer;
            updateSelfColor(
                selfMuted ? MUTED_DOT_COLOR : selfSpeaking ? SPEAKING_DOT_COLOR : IDLE_DOT_COLOR);
            updateSelfShape(selfMuted);

            smoothedMicLevel = smoothLevel(smoothedMicLevel, clientManager.getMicLevel(), dt);

            drawMeterRow(
                mc,
                self.getGameProfile()
                    .getId(),
                self.getCommandSenderName() + " [" + clientManager.getGroupDisplayName() + "]",
                MARGIN,
                selfY,
                lerpColor(selfColorFrom, selfColorTo, selfColorT),
                hudAlpha,
                selfSpeaking ? speakPulse : 1.0F,
                smoothedMicLevel,
                selfSquareness,
                selfHeightSquareness,
                selfWiden);
        }

        /** Framerate-independent attack/release easing toward {@code target} - see the tau constants. */
        private static float smoothLevel(float current, float target, float dt) {
            float tau = target > current ? LEVEL_ATTACK_TAU_MS : LEVEL_RELEASE_TAU_MS;
            return current + (target - current) * (1.0F - (float) Math.exp(-dt / tau));
        }

        /**
         * Morphs the self indicator between meter bars and the solid square when mute flips - the muted state
         * reads as the same steady square every other muted row shows, instead of dead 1px stubs. Muting
         * reunites the spaced bars smoothly ({@code QUAD_OUT}). Unmuting animates a linear progress through
         * the two struggle tracks ({@link #unmuteSquareness}/{@link #unmuteWiden}): the square widens, relaxes,
         * widens again, then pries apart past the resting spread and settles. Retargets from the current morph
         * value, so rapid mute toggles stay smooth.
         */
        private void updateSelfShape(boolean muted) {
            float target = muted ? 1.0F : 0.0F;
            if (target == selfSquarenessTarget) return;

            selfSquarenessTarget = target;
            if (this.selfShapeAnim != null) HudAnimations.unregister(this.selfShapeAnim);
            if (muted) {
                // The widen channel belongs to the unmute choreography alone; heights track the gaps.
                this.selfWiden = 0.0F;
                this.selfShapeAnim = HudAnimations.register(
                    new Animator().duration(SELF_SHAPE_MORPH_MS)
                        .curve(Interpolation.QUAD_OUT)
                        .bounds(this.selfSquareness, target)
                        .onUpdate(v -> {
                            this.selfSquareness = (float) v;
                            this.selfHeightSquareness = (float) v;
                        }));
            } else {
                float startSquareness = this.selfSquareness;
                float startHeight = this.selfHeightSquareness;
                this.selfShapeAnim = HudAnimations.register(
                    new Animator().duration(SELF_UNMUTE_MORPH_MS)
                        .curve(Interpolation.LINEAR)
                        .bounds(0.0F, 1.0F)
                        .onUpdate(v -> {
                            float p = (float) v;
                            this.selfSquareness = unmuteSquareness(p, startSquareness);
                            this.selfHeightSquareness = unmuteHeightSquareness(p, startHeight);
                            this.selfWiden = unmuteWiden(p);
                        }));
            }
            this.selfShapeAnim.animate();
        }

        /**
         * Crossfades the self indicator toward {@code target} when the mic state flips: the fade restarts from
         * the CURRENT blended color, so rapid flips (VA gate chatter) never jump. Same registry and
         * draw-pass-only discipline as every other animator here.
         */
        private void updateSelfColor(int target) {
            if (target == selfColorTo) return;

            selfColorFrom = lerpColor(selfColorFrom, selfColorTo, selfColorT);
            selfColorTo = target;
            selfColorT = 0.0F;
            if (this.selfColorAnim != null) HudAnimations.unregister(this.selfColorAnim);
            this.selfColorAnim = HudAnimations.register(
                new Animator().duration(SELF_COLOR_FADE_MS)
                    .curve(Interpolation.LINEAR)
                    .bounds(0.0F, 1.0F)
                    .onUpdate(v -> { this.selfColorT = (float) v; }));
            this.selfColorAnim.animate();
        }

        /**
         * Derives the whole-HUD fade target - an open GUI screen hides completely, a fresh chat line dims,
         * otherwise fully visible - and retargets the shared fade animator only when the target changes, so a
         * burst of chat messages just extends the hold and an interrupted fade continues from the current
         * alpha. Runs in the draw pass, honoring the draw-pass-only animator (un)registration rule.
         */
        private void updateHudFade(Minecraft mc) {
            float target;
            if (mc.currentScreen != null) {
                target = SCREEN_HIDDEN_ALPHA;
            } else if (Minecraft.getSystemTime() - lastChatAtMillis < CHAT_DIM_HOLD_MS) {
                target = CHAT_DIM_ALPHA;
            } else {
                target = 1.0F;
            }
            if (target == hudTargetAlpha) return;

            hudTargetAlpha = target;
            if (this.hudFade != null) HudAnimations.unregister(this.hudFade);
            this.hudFade = HudAnimations.register(
                new Animator().duration(HUD_FADE_MS)
                    .curve(target < this.hudAlpha ? Interpolation.QUAD_OUT : Interpolation.QUAD_IN)
                    .bounds(this.hudAlpha, target)
                    .onUpdate(v -> { this.hudAlpha = (float) v; }));
            this.hudFade.animate();
        }

        /**
         * Diffs the current muted+speaking lists against the animated rows: new rows appear at their slot and
         * fade in, surviving rows whose slot changed slide there, vanished rows start fading out (their slot is
         * freed immediately - a brief overlap with a sliding neighbor is fine).
         */
        private void reconcile(VoiceClientManager clientManager, int selfY, long now) {
            List<TargetRow> targets = buildTargets(clientManager, selfY, now);

            for (TargetRow target : targets) {
                AnimatedRow row = rows.get(target.uuid);
                if (row == null) {
                    row = new AnimatedRow(target.uuid, target.y);
                    rows.put(target.uuid, row);
                    row.enter();
                } else if (row.dying) {
                    row.revive();
                }
                row.label = target.label;
                row.dotColor = target.dotColor;
                if (Math.round(row.targetY) != target.y) {
                    row.slideTo(target.y);
                }
            }

            for (AnimatedRow row : rows.values()) {
                if (row.dying) continue;
                boolean live = false;
                for (TargetRow target : targets) {
                    if (target.uuid.equals(row.uuid)) {
                        live = true;
                        break;
                    }
                }
                if (!live) row.die();
            }
        }

        /**
         * Target slots this frame, bottom-up above the pinned self row: muted roster players first (shown
         * unconditionally as confirmation the mute is active - muted audio is dropped at UDP ingress, so this
         * client never learns whether they're talking), then everyone currently speaking or within the
         * post-speech linger window - a just-finished speaker keeps their slot (meter draining to silence) for
         * {@value #SPEAKING_LINGER_MS}ms before the removal fade starts.
         */
        private List<TargetRow> buildTargets(VoiceClientManager clientManager, int selfY, long now) {
            PlayerVoiceSettings settings = PlayerVoiceSettings.getInstance();
            Map<UUID, String> roster = clientManager.getRosterView();

            List<TargetRow> muted = new ArrayList<>();
            Set<UUID> mutedIds = new HashSet<>();
            for (Map.Entry<UUID, String> entry : roster.entrySet()) {
                if (!settings.isMuted(entry.getKey())) continue;
                mutedIds.add(entry.getKey());
                muted.add(new TargetRow(entry.getKey(), entry.getValue(), MUTED_DOT_COLOR, 0));
            }
            sortByLabel(muted);

            Set<UUID> liveNow = Collections.emptySet();
            VoiceSourceManager sourceManager = clientManager.getVoiceSourceManager();
            if (sourceManager != null) {
                liveNow = sourceManager.getSpeakingSourceIds();
                for (UUID sourceId : liveNow) {
                    lastSpeakingAtMillis.put(sourceId, now);
                }
            }

            List<TargetRow> speaking = new ArrayList<>();
            Iterator<Map.Entry<UUID, Long>> it = lastSpeakingAtMillis.entrySet()
                .iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Long> entry = it.next();
                UUID sourceId = entry.getKey();
                // Linger expired, or a lingering (non-live) speaker left the game: stop tracking, the row
                // falls out of the targets and fades away.
                boolean lingering = !liveNow.contains(sourceId);
                if (now - entry.getValue() > SPEAKING_LINGER_MS || (lingering && !roster.containsKey(sourceId))) {
                    it.remove();
                    continue;
                }
                // A speaker muted mid-linger already has a muted row; don't show them twice.
                if (mutedIds.contains(sourceId)) continue;

                String label = clientManager.resolveName(sourceId)
                    .orElseGet(() -> shortId(sourceId));
                speaking.add(new TargetRow(sourceId, label, SPEAKING_DOT_COLOR, 0));
            }
            sortByLabel(speaking);

            List<TargetRow> targets = new ArrayList<>(muted.size() + speaking.size());
            int slot = 1;
            for (TargetRow row : muted) targets.add(row.atY(selfY - slot++ * LINE_HEIGHT));
            for (TargetRow row : speaking) targets.add(row.atY(selfY - slot++ * LINE_HEIGHT));
            return targets;
        }

        private static void sortByLabel(List<TargetRow> rows) {
            rows.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label));
        }

        /**
         * Fallback label for a speaking sourceId whose roster entry hasn't arrived yet - the roster ADD travels
         * over the reliable channel while SourceAudioPacket is UDP, so a genuinely-speaking player can briefly
         * resolve to nothing right after they join. Shown until the roster catches up, never skipped.
         */
        private static String shortId(UUID sourceId) {
            return sourceId.toString()
                .substring(0, 8);
        }

        /** Removes rows whose fade-out finished; the actual flagging happens in the animator's onFinish. */
        private void prune() {
            Iterator<AnimatedRow> it = rows.values()
                .iterator();
            while (it.hasNext()) {
                AnimatedRow row = it.next();
                if (!row.dead) continue;
                row.disposeAnimators();
                it.remove();
            }
        }

        private void clearRows() {
            for (AnimatedRow row : rows.values()) row.disposeAnimators();
            rows.clear();
            lastSpeakingAtMillis.clear();
            // Reset the whole-HUD fade and self-indicator state too: a re-appearing HUD re-derives both on
            // its first frame.
            if (this.hudFade != null) {
                HudAnimations.unregister(this.hudFade);
                this.hudFade = null;
            }
            this.hudAlpha = 1.0F;
            this.hudTargetAlpha = 1.0F;
            if (this.selfColorAnim != null) {
                HudAnimations.unregister(this.selfColorAnim);
                this.selfColorAnim = null;
            }
            this.selfColorFrom = IDLE_DOT_COLOR;
            this.selfColorTo = IDLE_DOT_COLOR;
            this.selfColorT = 1.0F;
            this.smoothedMicLevel = 0.0F;
            this.lastLevelFrameMillis = 0L;
            if (this.selfShapeAnim != null) {
                HudAnimations.unregister(this.selfShapeAnim);
                this.selfShapeAnim = null;
            }
            this.selfSquareness = 0.0F;
            this.selfHeightSquareness = 0.0F;
            this.selfSquarenessTarget = 0.0F;
            this.selfWiden = 0.0F;
        }

        @Override
        public void dispose() {
            super.dispose();
            clearRows();
        }

        private static void drawRow(Minecraft mc, UUID uuid, String label, int x, int y, int dotColor, float alpha) {
            int alphaByte = Math.round(alpha * 255.0F);
            // Below ~4 the font renderer treats the color as opaque (it masks 0xFC000000), so skip early.
            if (alphaByte < 8) return;

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GuiDraw.drawRect(
                x + (INDICATOR_WIDTH - DOT_SIZE) / 2,
                y + 1,
                DOT_SIZE,
                DOT_SIZE,
                (alphaByte << 24) | (dotColor & 0xFFFFFF));
            drawHeadAndLabel(mc, uuid, label, x, y, alpha, alphaByte);
        }

        /**
         * A meter row (the self row, and every currently-speaking row): three level bars in the dot's slot
         * (bottom-aligned to the dot's baseline, center bar most sensitive), then the shared head+label tail.
         * {@code level} is the smoothed 0..1 audio level; {@code pulse} is the speaking breath, applied to the
         * BARS only - head and label stay steady; {@code squareness} drives the gap collapse,
         * {@code heightSquareness} the bar heights (split so the unmute pry rips apart full-height columns);
         * {@code widen} stretches the bars horizontally (the unmute struggle's square-widening beats). The
         * three morph channels are self-row-only - other rows pass zeroes.
         */
        private static void drawMeterRow(Minecraft mc, UUID uuid, String label, int x, int y, int color, float alpha,
            float pulse, float level, float squareness, float heightSquareness, float widen) {
            int alphaByte = Math.round(alpha * 255.0F);
            if (alphaByte < 8) return;

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            int argb = (Math.round(alpha * pulse * 255.0F) << 24) | (color & 0xFFFFFF);
            int baseline = y + 1 + DOT_SIZE;
            int barWidth = DOT_SIZE / 3;
            float midLevel = 1 + level * (DOT_SIZE + 1); // may overshoot the dot box by 1px, deliberately
            float sideLevel = 1 + level * (DOT_SIZE - 2);
            // Clamped to >= 1 for safety against extrapolation at the morph edges. Everything below stays
            // float all the way into drawRect: GUI pixels are 2-3 physical pixels, so sub-pixel heights and
            // positions render visibly smoother than whole-pixel steps on a 6px-tall meter.
            float mid = Math.max(1.0F, midLevel + (DOT_SIZE - midLevel) * heightSquareness);
            float side = Math.max(1.0F, sideLevel + (DOT_SIZE - sideLevel) * heightSquareness);
            // The gap collapses with squareness, and the cluster stays centered in the indicator column: the
            // bars converge toward the middle until, fully muted, they sit flush as the centered solid square.
            // The widen channel stretches each bar; with the bars flush that reads as the square widening.
            float barW = barWidth + widen * (SELF_WIDEN_PX / 3.0F);
            float gapNow = BAR_GAP * (1.0F - squareness);
            float stride = barW + gapNow;
            float clusterWidth = barW * 3 + gapNow * 2;
            float startX = x + (INDICATOR_WIDTH - clusterWidth) / 2.0F;
            GuiDraw.drawRect(startX, baseline - side, barW, side, argb);
            GuiDraw.drawRect(startX + stride, baseline - mid, barW, mid, argb);
            GuiDraw.drawRect(startX + stride * 2, baseline - side, barW, side, argb);
            drawHeadAndLabel(mc, uuid, label, x, y, alpha, alphaByte);
        }

        private static void drawHeadAndLabel(Minecraft mc, UUID uuid, String label, int x, int y, float alpha,
            int alphaByte) {
            // GuiDraw.drawRect's setupDrawColor() disables GL_TEXTURE_2D and never restores it - without this,
            // the head icon and font glyphs below render as untextured white rectangles. Go through Platform so
            // MUI2's GlStateManager cache stays coherent.
            Platform.setupDrawTex(true);

            int headX = x + INDICATOR_WIDTH + DOT_TEXT_GAP;
            VoiceSkinIcons.draw(mc, uuid, label, headX, y, HEAD_SIZE, alpha);

            mc.fontRenderer
                .drawStringWithShadow(label, headX + HEAD_SIZE + HEAD_TEXT_GAP, y, (alphaByte << 24) | TEXT_RGB);
        }

        /** Per-channel RGB lerp between two colors (alpha handled separately by the callers). */
        private static int lerpColor(int from, int to, float t) {
            int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
            int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
            int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
            return (r << 16) | (g << 8) | b;
        }
    }

    /**
     * A row's desired state this frame: identity, label, dot color, and target slot y.
     */
    private record TargetRow(UUID uuid, String label, int dotColor, int y) {

        TargetRow atY(int y) {
            return new TargetRow(uuid, label, dotColor, y);
        }
    }

    /**
     * Live animated state of one HUD row. Slide and fade are separate {@link Animator}s registered through
     * {@link HudAnimations} (always registered before {@code animate()}, see its class doc); retargeting swaps
     * in a fresh animator starting from the current value, so interrupted animations continue smoothly.
     * Animator callbacks run inside {@code HudAnimations.advanceAll()}, so they only mutate this row's fields -
     * (un)registration happens exclusively from the widget's draw pass to avoid concurrent list modification.
     */
    private static final class AnimatedRow {

        final UUID uuid;
        String label = "";
        int dotColor;
        float y;
        float targetY;
        float alpha;
        // Horizontal entrance/exit offset (0 = in place, negative = toward the screen edge).
        float xOffset;
        // Rightward bow while sliding between slots (reorder arc); independent of xOffset so entrance/exit
        // and reorder motion compose instead of fighting over one channel.
        float slideBow;
        // Smoothed 0..1 decoded-audio level for the row's meter bars (speaking rows only); eased in the
        // widget's draw pass with the shared attack/release taus.
        float smoothedLevel;
        // Motion-blur fake: eases toward ROW_MOTION_ALPHA while the row is positionally animating, back to
        // 1 at rest. Multiplied into the drawn alpha.
        float motionDim = 1.0F;
        boolean dying;
        boolean dead;
        private Animator slide;
        private Animator slideX;
        private Animator fade;

        AnimatedRow(UUID uuid, int startY) {
            this.uuid = uuid;
            this.y = startY;
            this.targetY = startY;
            this.xOffset = -ROW_SLIDE_X_PX; // born off to the left; enter() slides it in
        }

        void slideTo(int target) {
            this.targetY = target;
            if (this.slide != null) HudAnimations.unregister(this.slide);
            float startY = this.y;
            float startBow = this.slideBow;
            this.slide = HudAnimations.register(
                new Animator().duration(SLIDE_DURATION_MS)
                    .curve(Interpolation.CUBIC_OUT)
                    .bounds(startY, target)
                    .onUpdate(v -> {
                        this.y = (float) v;
                        // Bow from the slide's own (eased) progress: half-sine peaking mid-travel, zero at
                        // both ends. Blending in the retarget's starting bow keeps interruptions snap-free.
                        float progress = Math.abs(target - startY) < 0.001F ? 1.0F
                            : (this.y - startY) / (target - startY);
                        this.slideBow = startBow * (1.0F - progress)
                            + ROW_REORDER_ARC_PX * (float) Math.sin(Math.PI * progress);
                    }));
            this.slide.animate();
        }

        private void slideXTo(float target, IInterpolation curve, int duration) {
            if (this.slideX != null) HudAnimations.unregister(this.slideX);
            this.slideX = HudAnimations.register(
                new Animator().duration(duration)
                    .curve(curve)
                    .bounds(this.xOffset, target)
                    .onUpdate(v -> { this.xOffset = (float) v; }));
            this.slideX.animate();
        }

        void fadeTo(float target, IInterpolation curve, int duration, Runnable onFinish) {
            if (this.fade != null) HudAnimations.unregister(this.fade);
            Animator animator = new Animator().duration(duration)
                .curve(curve)
                .bounds(this.alpha, target)
                .onUpdate(v -> { this.alpha = (float) v; });
            if (onFinish != null) animator.onFinish(onFinish);
            this.fade = HudAnimations.register(animator);
            this.fade.animate();
        }

        /** Entrance: slide in from the left with a BACK_OUT overshoot, fading in on the way. */
        void enter() {
            slideXTo(0.0F, Interpolation.BACK_OUT, ROW_ENTER_MS);
            fadeTo(1.0F, Interpolation.QUAD_OUT, ROW_ENTER_FADE_MS, null);
        }

        /** Exit: BACK_IN anticipation (nudge right), then out to the left while fading away. */
        void die() {
            this.dying = true;
            slideXTo(-ROW_SLIDE_X_PX, Interpolation.BACK_IN, ROW_EXIT_MS);
            fadeTo(0.0F, Interpolation.QUAD_IN, ROW_EXIT_MS, () -> this.dead = true);
        }

        void revive() {
            this.dying = false;
            this.dead = false;
            slideXTo(0.0F, Interpolation.BACK_OUT, ROW_ENTER_MS);
            fadeTo(1.0F, Interpolation.QUAD_OUT, FADE_DURATION_MS, null);
        }

        /** Whether the row is currently in positional motion (slot slide, or entrance/exit travel). */
        boolean isMoving() {
            return (this.slide != null && this.slide.isAnimating())
                || (this.slideX != null && this.slideX.isAnimating());
        }

        void disposeAnimators() {
            if (this.slide != null) HudAnimations.unregister(this.slide);
            if (this.slideX != null) HudAnimations.unregister(this.slideX);
            if (this.fade != null) HudAnimations.unregister(this.fade);
            this.slide = null;
            this.slideX = null;
            this.fade = null;
        }
    }
}
