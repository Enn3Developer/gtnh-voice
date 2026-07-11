package com.enn3developer.gtnhvoice.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;

import org.jetbrains.annotations.NotNull;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.CustomModularScreen;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.utils.BooleanConsumer;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.value.DoubleValue;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.PageButton;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.ScrollingTextWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.api.client.IVoiceAddon;
import com.enn3developer.gtnhvoice.client.PlayerVoiceController;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.VoiceSkinIcons;
import com.enn3developer.gtnhvoice.client.api.ClientApiBackend;
import com.enn3developer.gtnhvoice.client.audio.AudioDeviceController;

/**
 * The client voice settings screen (ModularUI2), tabbed via {@link PagedWidget}/{@link PageButton}:
 * <ul>
 * <li><b>Output</b> (default): master volume, output device, HRTF, and the per-player volume/mute rows.</li>
 * <li><b>Input</b>: activation mode, mic gain, VA threshold, denoise, input device. While this tab is active
 * the mic monitor runs (see {@link VoiceClientManager#setMicMonitorActive}): every other voice source is muted
 * and the user's own processed mic plays back, ungated, so thresholds and gain can be tuned by ear.</li>
 * <li><b>Addons</b>: read-only list of registered client addons (name + description).</li>
 * </ul>
 * A red warning line above the tabs appears whenever no voice session is up - the live controls and the mic
 * monitor need one. Every control reads its current value from {@link Config}/{@link AudioDeviceController}
 * through dynamic value bindings and applies changes immediately - device/HRTF changes go through
 * {@link AudioDeviceController} (which already hotswaps live and persists), toggles/cycles set the
 * {@link Config} field and call {@link Config#save()} on click, sliders update the field live on every drag
 * tick (volume/gain also push onto the live session) and rely on the single {@link #onClose()} save (not per
 * tick, to avoid hammering disk I/O at ~60 writes/sec while dragging). Opened by
 * {@link VoiceSettingsKeyHandler}.
 */
public class VoiceSettingsScreen extends CustomModularScreen {

    private static final int ROW_HEIGHT = 16;
    private static final int LABEL_WIDTH = 85;
    private static final int VALUE_WIDTH = 50;
    private static final int HEAD_SIZE = 12;
    private static final int NAME_WIDTH = 80;
    private static final int MUTE_WIDTH = 50;
    private static final int ADDON_NAME_WIDTH = 100;
    private static final int WARNING_COLOR = 0xFFFF5555;
    private static final int DESCRIPTION_COLOR = 0xFFAAAAAA;

    private static final int OUTPUT_TAB = 0;
    private static final int INPUT_TAB = 1;
    private static final int ADDONS_TAB = 2;

    public VoiceSettingsScreen() {
        super(GtnhVoice.MODID);
    }

    @Override
    public @NotNull ModularPanel buildUI(ModularGuiContext context) {
        AudioDeviceController controller = AudioDeviceController.getInstance();
        // Refresh on every open - devices can change between openings.
        List<String> inputDevices = withDefaultOption(controller.listInputDevices());
        List<String> outputDevices = withDefaultOption(controller.listOutputDevices());

        PagedWidget.Controller tabController = new PagedWidget.Controller();

        return ModularPanel.defaultPanel("voice_settings")
            .width(340)
            .heightRel(0.85f)
            .child(
                Flow.column()
                    .sizeRel(1f)
                    .padding(7)
                    .childPadding(5)
                    .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                    .child(
                        new TextWidget<>(IKey.dynamic(VoiceSettingsScreen::sessionWarning)).widthRel(1f)
                            .height(10)
                            .color(WARNING_COLOR)
                            .textAlign(Alignment.Center))
                    .child(
                        Flow.row()
                            .widthRel(1f)
                            .height(ROW_HEIGHT)
                            .child(tabButton(OUTPUT_TAB, "Output", tabController))
                            .child(tabButton(INPUT_TAB, "Input", tabController))
                            .child(tabButton(ADDONS_TAB, "Addons", tabController)))
                    .child(
                        new PagedWidget<>().widthRel(1f)
                            .expanded()
                            .controller(tabController)
                            // Also fired with the initial page on init, so the monitor state is always in sync
                            // with the visible tab - including the OUTPUT_TAB default turning it off.
                            .onPageChange(
                                page -> VoiceClientManager.getInstance()
                                    .setMicMonitorActive(page == INPUT_TAB))
                            .addPage(outputPage(controller, outputDevices))
                            .addPage(inputPage(controller, inputDevices))
                            .addPage(addonsPage()))
                    .child(
                        new ButtonWidget<>().size(120, ROW_HEIGHT)
                            .overlay(IKey.str("Done"))
                            .onMousePressed(mouseButton -> {
                                close(true);
                                return true;
                            })));
    }

    private static String sessionWarning() {
        return VoiceClientManager.getInstance()
            .isSessionActive() ? "" : "WARNING: Voice session is inactive";
    }

    private static IWidget tabButton(int index, String label, PagedWidget.Controller tabController) {
        return new PageButton(index, tabController).expanded()
            .height(ROW_HEIGHT)
            .overlay(IKey.str(label));
    }

    /** The Output tab: everything the player hears - master volume, device, HRTF, per-player rows. */
    private static IWidget outputPage(AudioDeviceController controller, List<String> outputDevices) {
        return Flow.column()
            .sizeRel(1f)
            .childPadding(5)
            .child(sliderRow("Volume", 0, 100, () -> Config.outputVolume, value -> {
                Config.outputVolume = (int) Math.round(value);
                VoiceClientManager.getInstance()
                    .applyOutputVolume();
            }, () -> Config.outputVolume + "%"))
            .child(
                buttonRow(
                    () -> "Output Device: " + deviceDisplayName(controller.getOutputDevice()),
                    () -> controller.setOutputDevice(nextOption(outputDevices, controller.getOutputDevice()))))
            .child(buttonRow(() -> "HRTF: " + controller.getHrtfMode(), () -> {
                Config.HrtfMode[] modes = Config.HrtfMode.values();
                controller.setHrtfMode(
                    modes[(controller.getHrtfMode()
                        .ordinal() + 1) % modes.length]);
            }))
            .child(
                new TextWidget<>(IKey.str("Players")).widthRel(1f)
                    .textAlign(Alignment.Center))
            .child(playerList());
    }

    /**
     * The Input tab: everything about the mic. While this page is the active one, the mic monitor is live -
     * the page itself carries no monitor controls, {@code onPageChange} drives it.
     */
    private static IWidget inputPage(AudioDeviceController controller, List<String> inputDevices) {
        return Flow.column()
            .sizeRel(1f)
            .childPadding(5)
            .child(buttonRow(VoiceSettingsScreen::activationModeLabel, VoiceSettingsScreen::cycleActivationMode))
            .child(
                sliderRow(
                    "Mic Gain",
                    0,
                    200,
                    () -> Config.micGain,
                    value -> Config.micGain = (int) Math.round(value),
                    () -> Config.micGain + "%"))
            .child(
                sliderRow(
                    "VA Threshold",
                    -60,
                    0,
                    () -> Config.vaThresholdDb,
                    value -> Config.vaThresholdDb = value,
                    () -> Math.round(Config.vaThresholdDb) + " dB"))
            .child(toggleRow("Denoise", () -> Config.denoiseEnabled, enabled -> {
                Config.denoiseEnabled = enabled;
                Config.save();
                GtnhVoice.LOG.info("[VoiceSettings] Denoise enabled set to {}", enabled);
            }))
            .child(
                buttonRow(
                    () -> "Input Device: " + deviceDisplayName(controller.getInputDevice()),
                    () -> controller.setInputDevice(nextOption(inputDevices, controller.getInputDevice()))));
    }

    /** The Addons tab: read-only name + description rows off the registered addon handles. */
    private static IWidget addonsPage() {
        List<IVoiceAddon> addons = ClientApiBackend.getInstance()
            .addonsView();
        if (addons.isEmpty()) {
            return new TextWidget<>(IKey.str("No addons registered")).sizeRel(1f)
                .textAlign(Alignment.Center);
        }

        ListWidget<IWidget, ?> list = new ListWidget<>();
        for (IVoiceAddon addon : addons) {
            list.child(addonRow(addon));
        }
        return list.sizeRel(1f);
    }

    private static IWidget addonRow(IVoiceAddon addon) {
        return Flow.row()
            .widthRel(1f)
            .height(ROW_HEIGHT)
            .childPadding(4)
            .crossAxisAlignment(Alignment.CrossAxis.CENTER)
            .child(
                new TextWidget<>(IKey.str(addon.name())).width(ADDON_NAME_WIDTH)
                    .textAlign(Alignment.CenterLeft))
            .child(
                new ScrollingTextWidget(
                    IKey.str(
                        addon.description()
                            .orElse(""))).expanded()
                                .color(DESCRIPTION_COLOR));
    }

    /** The per-player volume/mute rows off the current roster snapshot (excluding self), name-sorted. */
    private static IWidget playerList() {
        UUID selfUuid = Minecraft.getMinecraft().thePlayer.getGameProfile()
            .getId();
        List<Map.Entry<UUID, String>> online = new ArrayList<>(
            VoiceClientManager.getInstance()
                .getRosterView()
                .entrySet());
        online.removeIf(
            entry -> entry.getKey()
                .equals(selfUuid));
        online.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getValue(), b.getValue()));

        if (online.isEmpty()) {
            return new TextWidget<>(IKey.str("No other players in voice chat")).widthRel(1f)
                .expanded()
                .textAlign(Alignment.Center);
        }

        ListWidget<IWidget, ?> list = new ListWidget<>();
        for (Map.Entry<UUID, String> entry : online) {
            list.child(playerRow(entry.getKey(), entry.getValue()));
        }
        return list.widthRel(1f)
            .expanded();
    }

    /**
     * One roster row. The controls capture their player's {@code UUID} in their value bindings, so volume/mute
     * changes route straight to {@link PlayerVoiceController} without any id-lookup table.
     */
    private static IWidget playerRow(UUID uuid, String name) {
        PlayerVoiceController controller = PlayerVoiceController.getInstance();
        return Flow.row()
            .widthRel(1f)
            .height(ROW_HEIGHT)
            .childPadding(4)
            .crossAxisAlignment(Alignment.CrossAxis.CENTER)
            .child(new PlayerHeadWidget(uuid, name).size(HEAD_SIZE))
            .child(new ScrollingTextWidget(IKey.str(name)).width(NAME_WIDTH))
            .child(
                GuiWidgets.flatSlider()
                    .value(
                        new DoubleValue.Dynamic(
                            () -> controller.getVolume(uuid) * 100.0,
                            value -> controller.setVolume(uuid, (float) (value / 100.0))))
                    .bounds(0, 200)
                    .expanded())
            .child(
                new TextWidget<>(IKey.dynamic(() -> Math.round(controller.getVolume(uuid) * 100) + "%"))
                    .width(VALUE_WIDTH - 15)
                    .textAlign(Alignment.CenterRight))
            .child(
                new ToggleButton().size(MUTE_WIDTH, ROW_HEIGHT)
                    .value(new BoolValue.Dynamic(() -> controller.isMuted(uuid), muted -> {
                        controller.setMuted(uuid, muted);
                        GtnhVoice.LOG.info("[PlayerVoice] {} muted set to {}", uuid, muted);
                    }))
                    .overlay(IKey.dynamic(() -> controller.isMuted(uuid) ? "Unmute" : "Mute")));
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

    private static void cycleActivationMode() {
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
        // The monitor is tab-scoped; closing the screen from the Input tab must not leave it running.
        VoiceClientManager.getInstance()
            .setMicMonitorActive(false);
        // Single disk write for the sliders' live drag updates (and a safety net for everything else).
        Config.save();
        GtnhVoice.LOG.info(
            "[VoiceSettings] Saved: volume {}%, mic gain {}%, VA threshold {} dB",
            Config.outputVolume,
            Config.micGain,
            Math.round(Config.vaThresholdDb));
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

    /**
     * Face+hat head icon rendered through the shared {@link VoiceSkinIcons} resolver/drawer (also used by the
     * HUD); drawn at the widget's local origin, which MUI2's viewport transform has already positioned.
     */
    private static final class PlayerHeadWidget extends Widget<PlayerHeadWidget> {

        private final UUID uuid;
        private final String name;

        PlayerHeadWidget(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
            VoiceSkinIcons.draw(Minecraft.getMinecraft(), uuid, name, 0, 0, HEAD_SIZE);
        }
    }
}
