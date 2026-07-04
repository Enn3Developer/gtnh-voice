package com.enn3developer.gtnhvoice.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.PlayerVoiceController;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.VoiceSkinIcons;
import com.github.bsideup.jabel.Desugar;

import cpw.mods.fml.client.config.GuiSlider;

/**
 * Per-player volume/mute screen: one row per online voice-roster player (excluding self) with head icon, name, a
 * 0-200% volume slider (default 100%), and a mute toggle. Opened from {@link GuiVoiceSettings}'s Players button,
 * which is where {@link #parent} comes from for Done to return to. Reuses that screen's hand-rolled
 * scroll/scissor pattern since the row list can exceed the window height at higher GUI scales.
 * <p>
 * Every change applies live through {@link PlayerVoiceController} (updates the concurrent map/set, plus a posted
 * gain command if that player currently has an active source) on every slider tick / button click; disk
 * persistence happens once in {@link #onGuiClosed}, not per tick, matching {@link GuiVoiceSettings}'s pattern.
 */
public class GuiPlayerVoiceSettings extends GuiScreen implements GuiSlider.ISlider {

    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 6;
    private static final int ROW_PITCH = ROW_HEIGHT + ROW_GAP;
    private static final int SCROLL_STEP = 16;

    private static final int HEAD_SIZE = 12;
    private static final int HEAD_TEXT_GAP = 4;
    private static final int NAME_WIDTH = 90;
    private static final int NAME_SLIDER_GAP = 6;
    private static final int SLIDER_WIDTH = 140;
    private static final int SLIDER_BUTTON_GAP = 6;
    private static final int MUTE_BUTTON_WIDTH = 70;
    private static final int ROW_WIDTH = HEAD_SIZE + HEAD_TEXT_GAP
        + NAME_WIDTH
        + NAME_SLIDER_GAP
        + SLIDER_WIDTH
        + SLIDER_BUTTON_GAP
        + MUTE_BUTTON_WIDTH;

    private static final int BUTTON_DONE = 0;

    private final GuiScreen parent;
    private final List<PlayerRow> rows = new ArrayList<>();

    private GuiButton doneButton;
    private int contentTop;
    private int contentBottom;
    private int contentHeight;
    private int maxScroll;
    private int scrollOffset;

    public GuiPlayerVoiceSettings(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        rows.clear();
        scrollOffset = 0;

        contentTop = 26;
        contentBottom = height - 34;

        int x = (width - ROW_WIDTH) / 2;
        int y = 0;
        int nextId = 1;

        UUID selfUuid = mc.thePlayer.getGameProfile()
            .getId();
        List<Map.Entry<UUID, String>> online = new ArrayList<>(
            VoiceClientManager.getInstance()
                .getRosterView()
                .entrySet());
        online.removeIf(
            entry -> entry.getKey()
                .equals(selfUuid));
        online.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getValue(), b.getValue()));

        PlayerVoiceController controller = PlayerVoiceController.getInstance();
        int sliderX = x + HEAD_SIZE + HEAD_TEXT_GAP + NAME_WIDTH + NAME_SLIDER_GAP;
        int buttonX = sliderX + SLIDER_WIDTH + SLIDER_BUTTON_GAP;

        for (Map.Entry<UUID, String> entry : online) {
            UUID uuid = entry.getKey();
            String name = entry.getValue();

            PlayerVolumeSlider slider = new PlayerVolumeSlider(
                nextId++,
                sliderX,
                uuid,
                Math.round(controller.getVolume(uuid) * 100));
            PlayerMuteButton muteButton = new PlayerMuteButton(nextId++, buttonX, uuid, controller.isMuted(uuid));

            buttonList.add(slider);
            buttonList.add(muteButton);
            rows.add(new PlayerRow(uuid, name, slider, muteButton, y));
            y += ROW_PITCH;
        }

        contentHeight = Math.max(0, y - ROW_GAP);
        maxScroll = Math.max(0, contentHeight - (contentBottom - contentTop));

        doneButton = new GuiButton(BUTTON_DONE, (width - 200) / 2, height - 26, 200, 20, "Done");
        buttonList.add(doneButton);

        updateScroll(0);
    }

    private void updateScroll(int delta) {
        scrollOffset = clamp(scrollOffset + delta, 0, maxScroll);
        for (PlayerRow row : rows) {
            int rowY = contentTop + row.baseY - scrollOffset;
            row.slider.yPosition = rowY;
            row.muteButton.yPosition = rowY;
            boolean visible = rowY + ROW_HEIGHT > contentTop && rowY < contentBottom;
            row.slider.visible = visible;
            row.muteButton.visible = visible;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();

        if (maxScroll <= 0) return;

        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;

        updateScroll(wheel > 0 ? -SCROLL_STEP : SCROLL_STEP);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Player Volume & Mute", width / 2, 8, 0xFFFFFF);

        if (rows.isEmpty()) {
            drawCenteredString(fontRendererObj, "No other players in voice chat", width / 2, height / 2, 0xAAAAAA);
        }

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        applyContentScissor();

        int x = (width - ROW_WIDTH) / 2;
        for (PlayerRow row : rows) {
            int rowY = contentTop + row.baseY - scrollOffset;
            if (rowY + ROW_HEIGHT <= contentTop || rowY >= contentBottom) continue;

            VoiceSkinIcons.draw(mc, row.uuid, row.name, x, rowY + (ROW_HEIGHT - HEAD_SIZE) / 2, HEAD_SIZE);

            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            fontRendererObj.drawStringWithShadow(
                trimToWidth(row.name, NAME_WIDTH - HEAD_TEXT_GAP),
                x + HEAD_SIZE + HEAD_TEXT_GAP,
                rowY + (ROW_HEIGHT - fontRendererObj.FONT_HEIGHT) / 2,
                0xFFFFFF);

            row.slider.drawButton(mc, mouseX, mouseY);
            row.muteButton.drawButton(mc, mouseX, mouseY);
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        doneButton.drawButton(mc, mouseX, mouseY);

        if (maxScroll > 0) {
            drawScrollbar();
        }
    }

    private String trimToWidth(String text, int maxWidth) {
        if (fontRendererObj.getStringWidth(text) <= maxWidth) return text;
        int ellipsisWidth = fontRendererObj.getStringWidth("...");
        return fontRendererObj.trimStringToWidth(text, Math.max(0, maxWidth - ellipsisWidth)) + "...";
    }

    /**
     * Content-area clip box in real framebuffer pixels (scissor origin is bottom-left, GUI coordinates are
     * top-down and scaled by {@link ScaledResolution}'s GUI-scale factor) - same approach as
     * {@code GuiVoiceSettings}'s own scroll pattern.
     */
    private void applyContentScissor() {
        ScaledResolution res = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        double scale = res.getScaleFactor();
        int scissorY = mc.displayHeight - (int) Math.round(contentBottom * scale);
        int scissorHeight = (int) Math.round((contentBottom - contentTop) * scale);
        GL11.glScissor(0, scissorY, mc.displayWidth, scissorHeight);
    }

    private void drawScrollbar() {
        int trackX = (width + ROW_WIDTH) / 2 + 8;
        int trackHeight = contentBottom - contentTop;
        drawRect(trackX, contentTop, trackX + 4, contentBottom, 0x55000000);

        int thumbHeight = Math.max(20, trackHeight * trackHeight / contentHeight);
        int thumbY = contentTop + scrollOffset * (trackHeight - thumbHeight) / maxScroll;
        drawRect(trackX, thumbY, trackX + 4, thumbY + thumbHeight, 0xFFAAAAAA);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BUTTON_DONE) {
            mc.displayGuiScreen(parent);
            return;
        }

        if (button instanceof PlayerMuteButton muteButton) {
            muteButton.muted = !muteButton.muted;
            muteButton.displayString = muteButton.muted ? "Unmute" : "Mute";
            PlayerVoiceController.getInstance()
                .setMuted(muteButton.playerUuid, muteButton.muted);
            GtnhVoice.LOG.info("[PlayerVoice] {} muted set to {}", muteButton.playerUuid, muteButton.muted);
        }
    }

    @Override
    public void onChangeSliderValue(GuiSlider slider) {
        if (slider instanceof PlayerVolumeSlider volumeSlider) {
            PlayerVoiceController.getInstance()
                .setVolume(volumeSlider.playerUuid, volumeSlider.getValueInt() / 100.0f);
        }
    }

    @Override
    public void onGuiClosed() {
        Config.save();
    }

    @Desugar
    private record PlayerRow(UUID uuid, String name, PlayerVolumeSlider slider, PlayerMuteButton muteButton,
        int baseY) {}

    /**
     * Forge's {@link GuiSlider} carrying the {@code UUID} it controls, so {@link #onChangeSliderValue} (a single
     * shared callback for every row's slider) can dispatch without an id-lookup table.
     */
    private final class PlayerVolumeSlider extends GuiSlider {

        final UUID playerUuid;

        PlayerVolumeSlider(int id, int x, UUID playerUuid, int currentPercent) {
            super(
                id,
                x,
                0,
                SLIDER_WIDTH,
                ROW_HEIGHT,
                "Volume: ",
                "%",
                0,
                200,
                currentPercent,
                false,
                true,
                GuiPlayerVoiceSettings.this);
            this.playerUuid = playerUuid;
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            super.mouseReleased(mouseX, mouseY);
            GtnhVoice.LOG.info("[PlayerVoice] {} volume set to {}%", playerUuid, getValueInt());
        }
    }

    /**
     * Plain {@link GuiButton} carrying the {@code UUID} it controls, dispatched via {@code instanceof} in
     * {@link #actionPerformed} for the same reason as {@link PlayerVolumeSlider}.
     */
    private static final class PlayerMuteButton extends GuiButton {

        final UUID playerUuid;
        boolean muted;

        PlayerMuteButton(int id, int x, UUID playerUuid, boolean muted) {
            super(id, x, 0, MUTE_BUTTON_WIDTH, ROW_HEIGHT, muted ? "Unmute" : "Mute");
            this.playerUuid = playerUuid;
            this.muted = muted;
        }
    }
}
