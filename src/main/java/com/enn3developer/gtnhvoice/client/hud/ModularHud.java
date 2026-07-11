package com.enn3developer.gtnhvoice.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.screen.ModularScreen;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Renders a read-only MUI2 {@link ModularScreen} as an in-game HUD.
 *
 * <p>
 * The screen is never opened as Minecraft's current screen. Instead we
 * drive its lifecycle by hand, the same way MUI2's own OverlayStack does:
 * {@code constructOverlay} -> {@code onResize} -> per tick {@code onUpdate}
 * -> per frame {@code onFrameUpdate} + {@code drawScreen}.
 *
 * <p>
 * No input events are forwarded, so the HUD is inherently non-interactive.
 * The mouse position is parked far off-screen so no widget ever hovers.
 *
 * <p>
 * Register once on the client:
 *
 * <pre>
 *
 * {
 *     &#64;code
 *     ModularHud hud = new ModularHud(myScreen);
 *     hud.register();
 * }
 * </pre>
 */
@Lwjgl3Aware
public class ModularHud {

    private final ModularScreen screen;
    private int lastWidth = -1, lastHeight = -1;

    public ModularHud(ModularScreen screen) {
        this.screen = screen;
        // Dummy wrapper — required by the ModularScreen lifecycle, never displayed.
        this.screen.constructOverlay(new GuiScreen() {});
    }

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance()
            .bus()
            .register(this); // ClientTickEvent bus in 1.7.10
    }

    public ModularScreen getScreen() {
        return screen;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;
        this.screen.onUpdate(); // widget tick logic (onUpdateListener etc.)
    }

    @SubscribeEvent(receiveCanceled = true)
    public void onRenderHud(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;
        // Deliberately NOT skipped while a GUI screen is open: the HUD keeps rendering (underneath the GUI)
        // so its widgets can animate a fade-out/in on screen open/close - once fully faded they draw nothing.
        // Our animators are parented (see HudAnimations), so MUI2's AnimatorManager ticking during an open
        // GUI never double-advances them.

        // 1. Advance HUD-driven animators by real elapsed time
        HudAnimations.advanceAll();

        // 2. (Re)layout on resolution/GUI-scale change; also triggers the
        // initial panel init + onOpen on the first frame.
        ScaledResolution res = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int w = res.getScaledWidth(), h = res.getScaledHeight();
        if (w != this.lastWidth || h != this.lastHeight) {
            this.lastWidth = w;
            this.lastHeight = h;
            this.screen.onResize(w, h);
        }

        // 3. Frame state: mouse parked off-screen -> nothing hovers,
        // partial ticks available to drawables.
        this.screen.getContext()
            .updateState(-9999, -9999, event.partialTicks);
        this.screen.onFrameUpdate();

        // 4. Draw the widget tree
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        this.screen.drawScreen();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }
}
