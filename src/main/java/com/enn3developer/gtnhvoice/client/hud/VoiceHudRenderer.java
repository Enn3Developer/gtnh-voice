package com.enn3developer.gtnhvoice.client.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.client.HeadIconCache;
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
    private static final int HEAD_SIZE = 8;
    private static final int HEAD_TEXT_GAP = 3;
    private static final int LINE_HEIGHT = 10;
    private static final int MARGIN = 2;
    private static final int SPEAKING_DOT_COLOR = 0xFF55FF55;
    private static final int MUTED_DOT_COLOR = 0xFFFF5555;
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final String MUTED_LABEL = "Microphone muted";

    // MC 1.7.10's SkinManager/ImageBufferDownload normalizes every downloaded skin onto a fixed 64x32 canvas
    // regardless of the source image's format (legacy 64x32 or the newer 64x64 layout) - see
    // ImageBufferDownload#parseUserSkin, which always allocates a 64x32 BufferedImage. So the face/hat UVs
    // below are always sampled against a 64x32 tile, never 64x64.
    private static final float SKIN_TEX_WIDTH = 64.0F;
    private static final float SKIN_TEX_HEIGHT = 32.0F;
    private static final float FACE_U = 8.0F;
    private static final float FACE_V = 8.0F;
    private static final float HAT_U = 40.0F;
    private static final float HAT_V = 8.0F;

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

        List<SpeakerRow> rows = buildRows(clientManager);
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

        for (SpeakerRow row : rows) {
            Gui.drawRect(x, y + 1, x + DOT_SIZE, y + 1 + DOT_SIZE, SPEAKING_DOT_COLOR);

            int headX = x + DOT_SIZE + DOT_TEXT_GAP;
            drawHeadIcon(mc, row.uuid, row.label, headX, y);

            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            fontRenderer.drawStringWithShadow(row.label, headX + HEAD_SIZE + HEAD_TEXT_GAP, y, TEXT_COLOR);
            y += LINE_HEIGHT;
        }
    }

    private List<SpeakerRow> buildRows(VoiceClientManager clientManager) {
        List<SpeakerRow> rows = new ArrayList<>();

        if (clientManager.isSpeaking()) {
            EntityPlayer self = Minecraft.getMinecraft().thePlayer;
            rows.add(
                new SpeakerRow(
                    self.getGameProfile()
                        .getId(),
                    self.getCommandSenderName() + " (you)"));
        }

        VoiceSourceManager sourceManager = clientManager.getVoiceSourceManager();
        if (sourceManager != null) {
            Set<UUID> speaking = sourceManager.getSpeakingSourceIds();
            List<SpeakerRow> others = new ArrayList<>(speaking.size());
            for (UUID sourceId : speaking) {
                String label = clientManager.resolveName(sourceId)
                    .orElseGet(() -> shortId(sourceId));
                others.add(new SpeakerRow(sourceId, label));
            }
            others.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label));
            rows.addAll(others);
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

    /**
     * Draws a {@value #HEAD_SIZE}x{@value #HEAD_SIZE} face+hat head icon at {@code (x, y)} for {@code uuid}.
     * Prefers a loaded {@link AbstractClientPlayer}'s own (already-resolved) skin to avoid a redundant lookup;
     * otherwise defers to {@link HeadIconCache}, which lazily triggers a load and returns the Steve fallback
     * until it resolves (or forever, if it never does).
     */
    private void drawHeadIcon(Minecraft mc, UUID uuid, String label, int x, int y) {
        ResourceLocation skin = resolveSkin(mc, uuid, label);

        mc.getTextureManager()
            .bindTexture(skin);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        Gui.func_152125_a(
            x,
            y,
            FACE_U,
            FACE_V,
            HEAD_SIZE,
            HEAD_SIZE,
            HEAD_SIZE,
            HEAD_SIZE,
            SKIN_TEX_WIDTH,
            SKIN_TEX_HEIGHT);
        Gui.func_152125_a(
            x,
            y,
            HAT_U,
            HAT_V,
            HEAD_SIZE,
            HEAD_SIZE,
            HEAD_SIZE,
            HEAD_SIZE,
            SKIN_TEX_WIDTH,
            SKIN_TEX_HEIGHT);

        GL11.glDisable(GL11.GL_BLEND);
    }

    private ResourceLocation resolveSkin(Minecraft mc, UUID uuid, String label) {
        if (mc.theWorld != null) {
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player instanceof AbstractClientPlayer && uuid.equals(
                    player.getGameProfile()
                        .getId())) {
                    return ((AbstractClientPlayer) player).getLocationSkin();
                }
            }
        }

        ResourceLocation cached = HeadIconCache.getInstance()
            .get(uuid, label);
        return cached != null ? cached : AbstractClientPlayer.locationStevePng;
    }

    private static final class SpeakerRow {

        final UUID uuid;
        final String label;

        SpeakerRow(UUID uuid, String label) {
            this.uuid = uuid;
            this.label = label;
        }
    }
}
