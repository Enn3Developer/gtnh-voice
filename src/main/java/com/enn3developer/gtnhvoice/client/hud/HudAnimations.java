package com.enn3developer.gtnhvoice.client.hud;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;

import com.cleanroommc.modularui.animation.BaseAnimator;
import com.cleanroommc.modularui.animation.IAnimator;
import com.cleanroommc.modularui.animation.ParallelAnimator;

/**
 * Registry for animators that must run on the in-game HUD, where MUI2's
 * {@code AnimatorManager} never ticks (it only advances animators during
 * {@code GuiScreenEvent.DrawScreenEvent.Pre}, i.e. while a GUI is open,
 * and it force-stops everything whenever a GUI closes).
 *
 * <p>
 * The trick: {@code BaseAnimator.resume()} only registers with
 * {@code AnimatorManager} when the animator has no parent. Wrapping an
 * animator in a throwaway {@link ParallelAnimator} sets that parent
 * (its constructor calls {@code setParent(this)} on every child), and the
 * parent field is used for nothing else in the library. The animator then
 * never touches {@code AnimatorManager} — we advance it ourselves.
 *
 * <p>
 * IMPORTANT: register an animator here BEFORE the first call to
 * {@code animate()} / {@code resume()}, otherwise it slips into
 * {@code AnimatorManager} once and will double-advance while a GUI is open.
 * Easiest is to register right after construction.
 */
public final class HudAnimations {

    private static final List<IAnimator> ANIMATORS = new ArrayList<>();
    private static long lastTime = 0L;

    private HudAnimations() {}

    /**
     * Detaches the animator from AnimatorManager and adds it to the
     * HUD-driven list. Call before animate(). Returns the animator
     * for fluent use.
     */
    public static <T extends BaseAnimator<T>> T register(T animator) {
        // Throwaway wrapper: its only job is to set itself as the parent,
        // which blocks AnimatorManager registration. We never animate it.
        new ParallelAnimator(animator);
        if (!ANIMATORS.contains(animator)) {
            ANIMATORS.add(animator);
        }
        return animator;
    }

    public static void unregister(IAnimator animator) {
        ANIMATORS.remove(animator);
    }

    /**
     * Advance all registered animators by the wall-clock time since the
     * last call. Safe to call every frame unconditionally:
     * {@code Animator.advance()} is a no-op while {@code !isAnimating()},
     * and since these animators are parented they are never advanced by
     * AnimatorManager — no double-advancing when a GUI is open.
     *
     * <p>
     * Called by {@link ModularHud} each render frame.
     */
    static void advanceAll() {
        long time = Minecraft.getSystemTime();
        int elapsed = IAnimator.getTimeDiff(lastTime, time);
        lastTime = time;

        if (elapsed == time || ANIMATORS.isEmpty()) {
            return;
        }
        if (elapsed <= 0) {
            return;
        }

        for (IAnimator animator : ANIMATORS) {
            if (animator.isPaused()) {
                continue;
            }

            animator.advance(elapsed);
        }
    }
}
