package com.enn3developer.gtnhvoice.client.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.client.PlayerVoiceSettings;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.VoiceClientSession;
import com.enn3developer.gtnhvoice.client.VoiceSkinIcons;
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
        List<SpeakerRow> mutedRows = buildMutedRows(clientManager);
        boolean selfMuted = clientManager.isMuted();
        if (rows.isEmpty() && mutedRows.isEmpty() && !selfMuted) return;

        FontRenderer fontRenderer = mc.fontRenderer;
        int x = MARGIN;
        int y = MARGIN;

        if (selfMuted) {
            Gui.drawRect(x, y + 1, x + DOT_SIZE, y + 1 + DOT_SIZE, MUTED_DOT_COLOR);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            fontRenderer.drawStringWithShadow(MUTED_LABEL, x + DOT_SIZE + DOT_TEXT_GAP, y, TEXT_COLOR);
            y += LINE_HEIGHT;
        }

        for (SpeakerRow row : mutedRows) {
            Gui.drawRect(x, y + 1, x + DOT_SIZE, y + 1 + DOT_SIZE, MUTED_DOT_COLOR);

            int headX = x + DOT_SIZE + DOT_TEXT_GAP;
            drawHeadIcon(mc, row.uuid, row.label, headX, y);

            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            fontRenderer
                .drawStringWithShadow(row.label + " (muted)", headX + HEAD_SIZE + HEAD_TEXT_GAP, y, TEXT_COLOR);
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

    /**
     * Every currently-online roster player this client has muted, regardless of whether they're actually
     * speaking - since muted audio is dropped at UDP ingress (see {@code VoiceClientManager#onUdpPacket}), this
     * client never learns whether a muted player is talking, so the marker is shown unconditionally as
     * confirmation the mute is active rather than as a speaking indicator.
     */
    private List<SpeakerRow> buildMutedRows(VoiceClientManager clientManager) {
        List<SpeakerRow> rows = new ArrayList<>();
        PlayerVoiceSettings settings = PlayerVoiceSettings.getInstance();

        for (Map.Entry<UUID, String> entry : clientManager.getRosterView()
            .entrySet()) {
            if (settings.isMuted(entry.getKey())) {
                rows.add(new SpeakerRow(entry.getKey(), entry.getValue()));
            }
        }

        rows.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label));
        return rows;
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
     * Draws a {@value #HEAD_SIZE}x{@value #HEAD_SIZE} face+hat head icon at {@code (x, y)} for {@code uuid}, via
     * the shared {@link VoiceSkinIcons} resolver/drawer (also used by the Players GUI).
     */
    private void drawHeadIcon(Minecraft mc, UUID uuid, String label, int x, int y) {
        VoiceSkinIcons.draw(mc, uuid, label, x, y, HEAD_SIZE);
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
