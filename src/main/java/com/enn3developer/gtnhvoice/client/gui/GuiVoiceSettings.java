package com.enn3developer.gtnhvoice.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.audio.AudioDeviceController;

import cpw.mods.fml.client.config.GuiSlider;

/**
 * Client-only personal-settings screen (per-player mute/volume is a later task): activation mode, VA
 * threshold/hangover, HUD and denoise toggles, input/output device and HRTF mode. Every control reads its
 * current value from {@link Config}/{@link AudioDeviceController} on open and applies changes immediately -
 * device/HRTF changes go through {@link AudioDeviceController} (which already hotswaps live and persists), the
 * rest set the {@link Config} field directly and call {@link Config#save()}. Opened by {@link
 * VoiceSettingsKeyHandler}.
 * <p>
 * Hand-rolled scrolling (mouse wheel + a scrollbar) since the control list can exceed the window height at
 * higher GUI scales: rows are laid out in an unscrolled column, then shifted by {@link #scrollOffset} and
 * clipped with a real {@code GL_SCISSOR_TEST} box so a row half-scrolled past the header/footer doesn't bleed
 * into them.
 */
public class GuiVoiceSettings extends GuiScreen implements GuiSlider.ISlider {

    private static final int CONTROL_WIDTH = 300;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 6;
    private static final int ROW_PITCH = ROW_HEIGHT + ROW_GAP;
    private static final int SCROLL_STEP = 16;

    private static final int BUTTON_DONE = 0;
    private static final int BUTTON_ACTIVATION_MODE = 1;
    private static final int SLIDER_VA_THRESHOLD = 2;
    private static final int SLIDER_VA_HANGOVER = 3;
    private static final int BUTTON_HUD = 4;
    private static final int BUTTON_DENOISE = 5;
    private static final int BUTTON_INPUT_DEVICE = 6;
    private static final int BUTTON_OUTPUT_DEVICE = 7;
    private static final int BUTTON_HRTF = 8;
    private static final int BUTTON_PLAYERS = 9;

    private final List<ScrollEntry> scrollEntries = new ArrayList<>();
    private List<String> inputDevices = Collections.emptyList();
    private List<String> outputDevices = Collections.emptyList();

    private GuiButton doneButton;
    private int contentTop;
    private int contentBottom;
    private int contentHeight;
    private int maxScroll;
    private int scrollOffset;

    @Override
    public void initGui() {
        buttonList.clear();
        scrollEntries.clear();
        scrollOffset = 0;

        AudioDeviceController controller = AudioDeviceController.getInstance();
        // Refresh on every open - devices can change between openings.
        inputDevices = withDefaultOption(controller.listInputDevices());
        outputDevices = withDefaultOption(controller.listOutputDevices());

        contentTop = 26;
        contentBottom = height - 34;

        int x = (width - CONTROL_WIDTH) / 2;
        int y = 0;

        addRow(new GuiButton(BUTTON_ACTIVATION_MODE, x, 0, CONTROL_WIDTH, ROW_HEIGHT, activationModeLabel()), y);
        y += ROW_PITCH;
        addRow(
            new VoiceSlider(
                SLIDER_VA_THRESHOLD,
                x,
                "VA Threshold: ",
                " dB",
                -60,
                0,
                Config.vaThresholdDb,
                "VA threshold"),
            y);
        y += ROW_PITCH;
        addRow(
            new VoiceSlider(SLIDER_VA_HANGOVER, x, "VA Hangover: ", " ms", 0, 2000, Config.vaHangoverMs, "VA hangover"),
            y);
        y += ROW_PITCH;
        addRow(new GuiButton(BUTTON_HUD, x, 0, CONTROL_WIDTH, ROW_HEIGHT, hudLabel()), y);
        y += ROW_PITCH;
        addRow(new GuiButton(BUTTON_DENOISE, x, 0, CONTROL_WIDTH, ROW_HEIGHT, denoiseLabel()), y);
        y += ROW_PITCH;
        addRow(
            new GuiButton(
                BUTTON_INPUT_DEVICE,
                x,
                0,
                CONTROL_WIDTH,
                ROW_HEIGHT,
                "Input Device: " + deviceDisplayName(controller.getInputDevice())),
            y);
        y += ROW_PITCH;
        addRow(
            new GuiButton(
                BUTTON_OUTPUT_DEVICE,
                x,
                0,
                CONTROL_WIDTH,
                ROW_HEIGHT,
                "Output Device: " + deviceDisplayName(controller.getOutputDevice())),
            y);
        y += ROW_PITCH;
        addRow(new GuiButton(BUTTON_HRTF, x, 0, CONTROL_WIDTH, ROW_HEIGHT, "HRTF: " + controller.getHrtfMode()), y);
        y += ROW_PITCH;
        addRow(new GuiButton(BUTTON_PLAYERS, x, 0, CONTROL_WIDTH, ROW_HEIGHT, "Players..."), y);
        y += ROW_PITCH;

        contentHeight = y - ROW_GAP;
        maxScroll = Math.max(0, contentHeight - (contentBottom - contentTop));

        doneButton = new GuiButton(BUTTON_DONE, (width - 200) / 2, height - 26, 200, 20, "Done");
        buttonList.add(doneButton);

        updateScroll(0);
    }

    private void addRow(GuiButton control, int baseY) {
        buttonList.add(control);
        scrollEntries.add(new ScrollEntry(control, baseY));
    }

    private void updateScroll(int delta) {
        scrollOffset = clamp(scrollOffset + delta, 0, maxScroll);
        for (ScrollEntry entry : scrollEntries) {
            int y = contentTop + entry.baseY - scrollOffset;
            entry.control.yPosition = y;
            entry.control.visible = y + ROW_HEIGHT > contentTop && y < contentBottom;
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
        drawCenteredString(fontRendererObj, "Voice Settings", width / 2, 8, 0xFFFFFF);

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        applyContentScissor();
        for (ScrollEntry entry : scrollEntries) {
            entry.control.drawButton(mc, mouseX, mouseY);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        doneButton.drawButton(mc, mouseX, mouseY);

        if (maxScroll > 0) {
            drawScrollbar();
        }
    }

    /**
     * Content-area clip box in real framebuffer pixels (scissor origin is bottom-left, GUI coordinates are
     * top-down and scaled by {@link ScaledResolution}'s GUI-scale factor).
     */
    private void applyContentScissor() {
        ScaledResolution res = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        double scale = res.getScaleFactor();
        int scissorY = mc.displayHeight - (int) Math.round(contentBottom * scale);
        int scissorHeight = (int) Math.round((contentBottom - contentTop) * scale);
        GL11.glScissor(0, scissorY, mc.displayWidth, scissorHeight);
    }

    private void drawScrollbar() {
        int trackX = (width + CONTROL_WIDTH) / 2 + 8;
        int trackHeight = contentBottom - contentTop;
        drawRect(trackX, contentTop, trackX + 4, contentBottom, 0x55000000);

        int thumbHeight = Math.max(20, trackHeight * trackHeight / contentHeight);
        int thumbY = contentTop + scrollOffset * (trackHeight - thumbHeight) / maxScroll;
        drawRect(trackX, thumbY, trackX + 4, thumbY + thumbHeight, 0xFFAAAAAA);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        AudioDeviceController controller = AudioDeviceController.getInstance();

        switch (button.id) {
            case BUTTON_DONE:
                mc.displayGuiScreen(null);
                break;
            case BUTTON_ACTIVATION_MODE: {
                Config.ActivationMode next = Config.getActivationMode() == Config.ActivationMode.VOICE_ACTIVATION
                    ? Config.ActivationMode.PUSH_TO_TALK
                    : Config.ActivationMode.VOICE_ACTIVATION;
                Config.activationMode = next.name();
                Config.save();
                button.displayString = activationModeLabel();
                GtnhVoice.LOG.info("[VoiceSettings] Activation mode set to {}", next);
                break;
            }
            case BUTTON_HUD:
                Config.hudEnabled = !Config.hudEnabled;
                Config.save();
                button.displayString = hudLabel();
                GtnhVoice.LOG.info("[VoiceSettings] HUD enabled set to {}", Config.hudEnabled);
                break;
            case BUTTON_DENOISE:
                Config.denoiseEnabled = !Config.denoiseEnabled;
                Config.save();
                button.displayString = denoiseLabel();
                GtnhVoice.LOG.info("[VoiceSettings] Denoise enabled set to {}", Config.denoiseEnabled);
                break;
            case BUTTON_INPUT_DEVICE: {
                String next = nextOption(inputDevices, controller.getInputDevice());
                controller.setInputDevice(next);
                button.displayString = "Input Device: " + deviceDisplayName(next);
                break;
            }
            case BUTTON_OUTPUT_DEVICE: {
                String next = nextOption(outputDevices, controller.getOutputDevice());
                controller.setOutputDevice(next);
                button.displayString = "Output Device: " + deviceDisplayName(next);
                break;
            }
            case BUTTON_HRTF: {
                Config.HrtfMode[] modes = Config.HrtfMode.values();
                Config.HrtfMode next = modes[(controller.getHrtfMode()
                    .ordinal() + 1) % modes.length];
                controller.setHrtfMode(next);
                button.displayString = "HRTF: " + next;
                break;
            }
            case BUTTON_PLAYERS:
                mc.displayGuiScreen(new GuiPlayerVoiceSettings(this));
                break;
            default:
                break;
        }
    }

    @Override
    public void onChangeSliderValue(GuiSlider slider) {
        if (slider.id == SLIDER_VA_THRESHOLD) {
            Config.vaThresholdDb = slider.getValue();
        } else if (slider.id == SLIDER_VA_HANGOVER) {
            Config.vaHangoverMs = slider.getValueInt();
        }
    }

    @Override
    public void onGuiClosed() {
        // Safety net alongside VoiceSlider's own save-on-release, in case the screen closes mid-drag.
        Config.save();
    }

    private static String activationModeLabel() {
        Config.ActivationMode mode = Config.getActivationMode();
        return "Activation Mode: "
            + (mode == Config.ActivationMode.VOICE_ACTIVATION ? "Voice Activation" : "Push To Talk");
    }

    private static String hudLabel() {
        return "HUD: " + (Config.hudEnabled ? "ON" : "OFF");
    }

    private static String denoiseLabel() {
        return "Denoise: " + (Config.denoiseEnabled ? "ON" : "OFF");
    }

    private static String deviceDisplayName(String device) {
        return device == null ? "System Default" : device;
    }

    /**
     * {@code null} (system default) followed by every enumerated device name, so cycling always includes an
     * explicit "go back to default" step even if enumeration is empty or fails.
     */
    private static List<String> withDefaultOption(List<String> devices) {
        List<String> options = new ArrayList<>(devices.size() + 1);
        options.add(null);
        options.addAll(devices);
        return options;
    }

    private static String nextOption(List<String> options, String current) {
        int index = options.indexOf(current);
        int nextIndex = (index < 0 ? 0 : index + 1) % options.size();
        return options.get(nextIndex);
    }

    private static final class ScrollEntry {

        final GuiButton control;
        final int baseY;

        ScrollEntry(GuiButton control, int baseY) {
            this.control = control;
            this.baseY = baseY;
        }
    }

    /**
     * Forge's {@link GuiSlider}, plus persist-and-log on release (not on every drag-tick, to avoid hammering disk
     * I/O at ~60 writes/sec while dragging) - {@link #onChangeSliderValue} still updates the live {@link Config}
     * field on every tick so the running activation gate picks it up immediately.
     */
    private final class VoiceSlider extends GuiSlider {

        private final String logLabel;

        VoiceSlider(int id, int x, String prefix, String suffix, double min, double max, double current,
            String logLabel) {
            super(
                id,
                x,
                0,
                CONTROL_WIDTH,
                ROW_HEIGHT,
                prefix,
                suffix,
                min,
                max,
                current,
                false,
                true,
                GuiVoiceSettings.this);
            this.logLabel = logLabel;
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            super.mouseReleased(mouseX, mouseY);
            Config.save();
            GtnhVoice.LOG.info("[VoiceSettings] {} set to {}", logLabel, getValueInt());
        }
    }
}
