package com.enn3developer.gtnhvoice.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import org.jetbrains.annotations.NotNull;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.CustomModularScreen;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.utils.BooleanConsumer;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.value.DoubleValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.audio.AudioDeviceController;

/**
 * Client-only personal-settings screen (ModularUI2): activation mode, VA threshold/hangover, HUD and denoise
 * toggles, input/output device and HRTF mode. Every control reads its current value from {@link Config}/{@link
 * AudioDeviceController} through dynamic value bindings (so labels always reflect live state without manual
 * relabeling) and applies changes immediately - device/HRTF changes go through {@link AudioDeviceController}
 * (which already hotswaps live and persists), toggles/cycles set the {@link Config} field and call {@link
 * Config#save()} on click, sliders update the field live on every drag tick and rely on the single {@link
 * #onClose()} save (not per tick, to avoid hammering disk I/O at ~60 writes/sec while dragging). Opened by
 * {@link VoiceSettingsKeyHandler} via {@link ClientGUI#open}.
 */
public class VoiceSettingsScreen extends CustomModularScreen {

    private static final int ROW_HEIGHT = 16;
    private static final int LABEL_WIDTH = 85;
    private static final int VALUE_WIDTH = 50;

    public VoiceSettingsScreen() {
        super(GtnhVoice.MODID);
    }

    @Override
    public @NotNull ModularPanel buildUI(ModularGuiContext context) {
        AudioDeviceController controller = AudioDeviceController.getInstance();
        // Refresh on every open - devices can change between openings.
        List<String> inputDevices = withDefaultOption(controller.listInputDevices());
        List<String> outputDevices = withDefaultOption(controller.listOutputDevices());

        return ModularPanel.defaultPanel("voice_settings")
            .width(300)
            .heightRel(0.85f)
            .child(
                Flow.column()
                    .sizeRel(1f)
                    .padding(7)
                    .childPadding(5)
                    .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                    .child(
                        new TextWidget<>(IKey.str("Voice Settings")).widthRel(1f)
                            .textAlign(Alignment.Center))
                    .child(
                        new ListWidget<>().widthRel(1f)
                            .expanded()
                            .child(buttonRow(VoiceSettingsScreen::activationModeLabel, this::cycleActivationMode))
                            .child(
                                sliderRow(
                                    "VA Threshold",
                                    -60,
                                    0,
                                    () -> Config.vaThresholdDb,
                                    value -> Config.vaThresholdDb = value,
                                    () -> Math.round(Config.vaThresholdDb) + " dB"))
                            .child(
                                sliderRow(
                                    "VA Hangover",
                                    0,
                                    2000,
                                    () -> Config.vaHangoverMs,
                                    value -> Config.vaHangoverMs = (int) Math.round(value),
                                    () -> Config.vaHangoverMs + " ms"))
                            .child(toggleRow("HUD", () -> Config.hudEnabled, enabled -> {
                                Config.hudEnabled = enabled;
                                Config.save();
                                GtnhVoice.LOG.info("[VoiceSettings] HUD enabled set to {}", enabled);
                            }))
                            .child(toggleRow("Denoise", () -> Config.denoiseEnabled, enabled -> {
                                Config.denoiseEnabled = enabled;
                                Config.save();
                                GtnhVoice.LOG.info("[VoiceSettings] Denoise enabled set to {}", enabled);
                            }))
                            .child(
                                buttonRow(
                                    () -> "Input Device: " + deviceDisplayName(controller.getInputDevice()),
                                    () -> controller
                                        .setInputDevice(nextOption(inputDevices, controller.getInputDevice()))))
                            .child(
                                buttonRow(
                                    () -> "Output Device: " + deviceDisplayName(controller.getOutputDevice()),
                                    () -> controller
                                        .setOutputDevice(nextOption(outputDevices, controller.getOutputDevice()))))
                            .child(buttonRow(() -> "HRTF: " + controller.getHrtfMode(), () -> {
                                Config.HrtfMode[] modes = Config.HrtfMode.values();
                                controller.setHrtfMode(
                                    modes[(controller.getHrtfMode()
                                        .ordinal() + 1) % modes.length]);
                            }))
                            .child(
                                buttonRow(() -> "Players...", () -> ClientGUI.open(new PlayerVoiceSettingsScreen()))))
                    .child(
                        new ButtonWidget<>().size(120, ROW_HEIGHT)
                            .overlay(IKey.str("Done"))
                            .onMousePressed(mouseButton -> {
                                close(true);
                                return true;
                            })));
    }

    /** A full-width button whose label re-evaluates every frame and whose click runs {@code onClick}. */
    private static IWidget buttonRow(Supplier<String> label, Runnable onClick) {
        return new ButtonWidget<>().widthRel(1f)
            .height(ROW_HEIGHT)
            .overlay(IKey.dynamic(label))
            .onMousePressed(mouseButton -> {
                onClick.run();
                return true;
            });
    }

    /** A full-width ON/OFF toggle bound live to a {@link Config} boolean. */
    private static IWidget toggleRow(String label, BooleanSupplier getter, BooleanConsumer setter) {
        return new ToggleButton().widthRel(1f)
            .height(ROW_HEIGHT)
            .value(new BoolValue.Dynamic(getter, setter))
            .overlay(IKey.dynamic(() -> label + ": " + (getter.getAsBoolean() ? "ON" : "OFF")));
    }

    /** Label + slider + live value readout; the setter fires on every drag tick (in-memory updates only). */
    private static IWidget sliderRow(String label, double min, double max, DoubleSupplier getter, DoubleConsumer setter,
        Supplier<String> valueLabel) {
        return Flow.row()
            .widthRel(1f)
            .height(ROW_HEIGHT)
            .childPadding(4)
            .crossAxisAlignment(Alignment.CrossAxis.CENTER)
            .child(
                new TextWidget<>(IKey.str(label)).width(LABEL_WIDTH)
                    .textAlign(Alignment.CenterLeft))
            .child(
                GuiWidgets.flatSlider()
                    .value(new DoubleValue.Dynamic(getter, setter))
                    .bounds(min, max)
                    .expanded())
            .child(
                new TextWidget<>(IKey.dynamic(valueLabel)).width(VALUE_WIDTH)
                    .textAlign(Alignment.CenterRight));
    }

    private void cycleActivationMode() {
        Config.ActivationMode next = Config.getActivationMode() == Config.ActivationMode.VOICE_ACTIVATION
            ? Config.ActivationMode.PUSH_TO_TALK
            : Config.ActivationMode.VOICE_ACTIVATION;
        Config.activationMode = next.name();
        Config.save();
        GtnhVoice.LOG.info("[VoiceSettings] Activation mode set to {}", next);
    }

    @Override
    public void onClose() {
        super.onClose();
        // Single disk write for the sliders' live drag updates (and a safety net for everything else).
        Config.save();
        GtnhVoice.LOG.info(
            "[VoiceSettings] Saved: VA threshold {} dB, VA hangover {} ms",
            Math.round(Config.vaThresholdDb),
            Config.vaHangoverMs);
    }

    private static String activationModeLabel() {
        Config.ActivationMode mode = Config.getActivationMode();
        return "Activation Mode: "
            + (mode == Config.ActivationMode.VOICE_ACTIVATION ? "Voice Activation" : "Push To Talk");
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
}
