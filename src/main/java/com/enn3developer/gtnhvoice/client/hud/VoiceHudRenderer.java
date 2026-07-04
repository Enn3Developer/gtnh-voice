package com.enn3developer.gtnhvoice.client.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.VoiceClientSession;
import com.enn3developer.gtnhvoice.client.source.VoiceSourceManager;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * The "who's talking" HUD: a compact top-left list of every player currently speaking, drawn from a fresh
 * snapshot of {@link VoiceSourceManager#getSpeakingSourceIds()} plus local {@link
 * VoiceClientManager#isSpeaking()} state every render tick. Purely a read/render layer - never mutates voice
 * state. Must be public: FML's ASM event bus subscriber scanning throws {@link IllegalAccessError} on non-public
 * listener classes under lwjgl3ify.
 */
public class VoiceHudRenderer {

    private static final int DOT_SIZE = 6;
    private static final int DOT_TEXT_GAP = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int MARGIN = 2;
    private static final int SPEAKING_DOT_COLOR = 0xFF55FF55;
    private static final int MUTED_DOT_COLOR = 0xFFFF5555;
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final String MUTED_LABEL = "Microphone muted";

    /**
     * Registers on {@link MinecraftForge#EVENT_BUS}, NOT {@code FMLCommonHandler.instance().bus()} - unlike
     * TickEvent (the FML lifecycle bus), RenderGameOverlayEvent is posted on the regular Forge event bus (see
     * GuiIngameForge's {@code pre}/{@code post} helpers).
     */
    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderGameOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;
        if (!Config.hudEnabled) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;
        if (mc.currentScreen != null) return;
        // Vanilla's F3 debug text renders in this exact top-left slot (see GuiIngameForge's TEXT
        // phase) - yield to it rather than overlapping.
        if (mc.gameSettings.showDebugInfo) return;

        VoiceClientManager clientManager = VoiceClientManager.getInstance();
        if (clientManager.getSession()
            .getState() != VoiceClientSession.State.CONNECTED) return;

        List<String> rows = buildRows(clientManager);
        boolean muted = clientManager.isMuted();
        if (rows.isEmpty() && !muted) return;

        FontRenderer fontRenderer = mc.fontRenderer;
        int x = MARGIN;
        int y = MARGIN;

        if (muted) {
            Gui.drawRect(x, y + 1, x + DOT_SIZE, y + 1 + DOT_SIZE, MUTED_DOT_COLOR);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            fontRenderer.drawStringWithShadow(MUTED_LABEL, x + DOT_SIZE + DOT_TEXT_GAP, y, TEXT_COLOR);
            y += LINE_HEIGHT;
        }

        for (String row : rows) {
            Gui.drawRect(x, y + 1, x + DOT_SIZE, y + 1 + DOT_SIZE, SPEAKING_DOT_COLOR);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            fontRenderer.drawStringWithShadow(row, x + DOT_SIZE + DOT_TEXT_GAP, y, TEXT_COLOR);
            y += LINE_HEIGHT;
        }
    }

    private List<String> buildRows(VoiceClientManager clientManager) {
        List<String> rows = new ArrayList<>();

        if (clientManager.isSpeaking()) {
            rows.add(Minecraft.getMinecraft().thePlayer.getCommandSenderName() + " (you)");
        }

        VoiceSourceManager sourceManager = clientManager.getVoiceSourceManager();
        if (sourceManager != null) {
            Set<UUID> speaking = sourceManager.getSpeakingSourceIds();
            List<String> otherNames = new ArrayList<>(speaking.size());
            for (UUID sourceId : speaking) {
                otherNames.add(
                    clientManager.resolveName(sourceId)
                        .orElseGet(() -> shortId(sourceId)));
            }
            otherNames.sort(String.CASE_INSENSITIVE_ORDER);
            rows.addAll(otherNames);
        }

        return rows;
    }

    /**
     * Fallback label for a speaking sourceId whose roster entry hasn't arrived yet - the roster ADD travels over
     * the reliable channel while SourceAudioPacket is UDP, so a genuinely-speaking player can briefly resolve to
     * nothing right after they join. Shown until the roster catches up, never skipped.
     */
    private String shortId(UUID sourceId) {
        return sourceId.toString()
            .substring(0, 8);
    }
}
