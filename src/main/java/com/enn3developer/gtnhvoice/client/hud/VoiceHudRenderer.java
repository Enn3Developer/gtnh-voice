package com.enn3developer.gtnhvoice.client.hud;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

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
import com.enn3developer.gtnhvoice.client.source.VoiceSourceManager;
import com.github.bsideup.jabel.Desugar;

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

    public void register() {
        new ModularHud(createScreen()).register();
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
     * and draws them bottom-up from the corner. Rows fade in on appearance, slide ({@link Interpolation
     * #CUBIC_OUT}) when their slot shifts, and fade out before being dropped; the pinned self row is drawn
     * directly every frame and never animates.
     */
    public static class SpeakerListWidget extends Widget<SpeakerListWidget> {

        private final LinkedHashMap<UUID, AnimatedRow> rows = new LinkedHashMap<>();

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

            reconcile(clientManager, selfY);
            prune();

            for (AnimatedRow row : rows.values()) {
                drawRow(mc, row.uuid, row.label, MARGIN, Math.round(row.y), row.dotColor, row.alpha);
            }

            // Pinned self row, drawn last so it stays on top while neighbors animate through. The dot doubles
            // as the mic-state indicator (muted/speaking/idle).
            boolean selfMuted = clientManager.isMuted();
            EntityPlayer self = mc.thePlayer;
            int selfDotColor = selfMuted ? MUTED_DOT_COLOR
                : clientManager.isSpeaking() ? SPEAKING_DOT_COLOR : IDLE_DOT_COLOR;
            drawRow(
                mc,
                self.getGameProfile()
                    .getId(),
                self.getCommandSenderName() + " [local]",
                MARGIN,
                selfY,
                selfDotColor,
                1.0F);
        }

        /**
         * Diffs the current muted+speaking lists against the animated rows: new rows appear at their slot and
         * fade in, surviving rows whose slot changed slide there, vanished rows start fading out (their slot is
         * freed immediately - a brief overlap with a sliding neighbor is fine).
         */
        private void reconcile(VoiceClientManager clientManager, int selfY) {
            List<TargetRow> targets = buildTargets(clientManager, selfY);

            for (TargetRow target : targets) {
                AnimatedRow row = rows.get(target.uuid);
                if (row == null) {
                    row = new AnimatedRow(target.uuid, target.y);
                    rows.put(target.uuid, row);
                    row.fadeTo(1.0F, Interpolation.QUAD_OUT, null);
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
         * client never learns whether they're talking), then everyone currently speaking.
         */
        private List<TargetRow> buildTargets(VoiceClientManager clientManager, int selfY) {
            PlayerVoiceSettings settings = PlayerVoiceSettings.getInstance();

            List<TargetRow> muted = new ArrayList<>();
            for (Map.Entry<UUID, String> entry : clientManager.getRosterView()
                .entrySet()) {
                if (!settings.isMuted(entry.getKey())) continue;
                muted.add(new TargetRow(entry.getKey(), entry.getValue(), MUTED_DOT_COLOR, 0));
            }
            sortByLabel(muted);

            List<TargetRow> speaking = new ArrayList<>();
            VoiceSourceManager sourceManager = clientManager.getVoiceSourceManager();
            if (sourceManager != null) {
                Set<UUID> speakingIds = sourceManager.getSpeakingSourceIds();
                for (UUID sourceId : speakingIds) {
                    String label = clientManager.resolveName(sourceId)
                        .orElseGet(() -> shortId(sourceId));
                    speaking.add(new TargetRow(sourceId, label, SPEAKING_DOT_COLOR, 0));
                }
                sortByLabel(speaking);
            }

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
            GuiDraw.drawRect(x, y + 1, DOT_SIZE, DOT_SIZE, (alphaByte << 24) | (dotColor & 0xFFFFFF));
            // GuiDraw.drawRect's setupDrawColor() disables GL_TEXTURE_2D and never restores it - without this,
            // the head icon and font glyphs below render as untextured white rectangles. Go through Platform so
            // MUI2's GlStateManager cache stays coherent.
            Platform.setupDrawTex(true);

            int headX = x + DOT_SIZE + DOT_TEXT_GAP;
            VoiceSkinIcons.draw(mc, uuid, label, headX, y, HEAD_SIZE, alpha);

            mc.fontRenderer
                .drawStringWithShadow(label, headX + HEAD_SIZE + HEAD_TEXT_GAP, y, (alphaByte << 24) | TEXT_RGB);
        }
    }

    /**
     * A row's desired state this frame: identity, label, dot color, and target slot y.
     */
    @Desugar
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
        boolean dying;
        boolean dead;
        private Animator slide;
        private Animator fade;

        AnimatedRow(UUID uuid, int startY) {
            this.uuid = uuid;
            this.y = startY;
            this.targetY = startY;
        }

        void slideTo(int target) {
            this.targetY = target;
            if (this.slide != null) HudAnimations.unregister(this.slide);
            this.slide = HudAnimations.register(
                new Animator().duration(SLIDE_DURATION_MS)
                    .curve(Interpolation.CUBIC_OUT)
                    .bounds(this.y, target)
                    .onUpdate(v -> { this.y = (float) v; }));
            this.slide.animate();
        }

        void fadeTo(float target, IInterpolation curve, Runnable onFinish) {
            if (this.fade != null) HudAnimations.unregister(this.fade);
            Animator animator = new Animator().duration(FADE_DURATION_MS)
                .curve(curve)
                .bounds(this.alpha, target)
                .onUpdate(v -> { this.alpha = (float) v; });
            if (onFinish != null) animator.onFinish(onFinish);
            this.fade = HudAnimations.register(animator);
            this.fade.animate();
        }

        void die() {
            this.dying = true;
            fadeTo(0.0F, Interpolation.QUAD_IN, () -> this.dead = true);
        }

        void revive() {
            this.dying = false;
            this.dead = false;
            fadeTo(1.0F, Interpolation.QUAD_OUT, null);
        }

        void disposeAnimators() {
            if (this.slide != null) HudAnimations.unregister(this.slide);
            if (this.fade != null) HudAnimations.unregister(this.fade);
            this.slide = null;
            this.fade = null;
        }
    }
}
