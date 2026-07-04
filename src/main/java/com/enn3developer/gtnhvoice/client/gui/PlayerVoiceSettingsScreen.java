package com.enn3developer.gtnhvoice.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;

import org.jetbrains.annotations.NotNull;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.CustomModularScreen;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.value.DoubleValue;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.ScrollingTextWidget;
import com.cleanroommc.modularui.widgets.SliderWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.PlayerVoiceController;
import com.enn3developer.gtnhvoice.client.VoiceClientManager;
import com.enn3developer.gtnhvoice.client.VoiceSkinIcons;

/**
 * Per-player volume/mute screen (ModularUI2): one row per online voice-roster player (excluding self) with head
 * icon, scrolling name, a 0-200% volume slider (default 100%), and a mute toggle. Opened from {@link
 * VoiceSettingsScreen}'s Players button; {@link #openParentOnClose(boolean)} brings that screen back on Done/Escape.
 * <p>
 * Every change applies live through {@link PlayerVoiceController} (updates the concurrent map/set, plus a posted
 * gain command if that player currently has an active source) on every slider tick / toggle click; disk
 * persistence happens once in {@link #onClose()}, not per tick, matching {@link VoiceSettingsScreen}'s pattern.
 */
public class PlayerVoiceSettingsScreen extends CustomModularScreen {

    private static final int ROW_HEIGHT = 16;
    private static final int HEAD_SIZE = 12;
    private static final int NAME_WIDTH = 80;
    private static final int VALUE_WIDTH = 35;
    private static final int MUTE_WIDTH = 50;

    public PlayerVoiceSettingsScreen() {
        super(GtnhVoice.MODID);
        // Done/Escape reopens the main voice settings screen this one was opened from.
        openParentOnClose(true);
    }

    @Override
    public @NotNull ModularPanel buildUI(ModularGuiContext context) {
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

        IWidget content;
        if (online.isEmpty()) {
            content = new TextWidget<>(IKey.str("No other players in voice chat")).widthRel(1f)
                .expanded()
                .textAlign(Alignment.Center);
        } else {
            ListWidget<IWidget, ?> list = new ListWidget<>();
            for (Map.Entry<UUID, String> entry : online) {
                list.child(playerRow(entry.getKey(), entry.getValue()));
            }
            content = list.widthRel(1f)
                .expanded();
        }

        return ModularPanel.defaultPanel("player_voice_settings")
            .width(340)
            .heightRel(0.85f)
            .child(
                Flow.column()
                    .sizeRel(1f)
                    .padding(7)
                    .childPadding(5)
                    .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                    .child(
                        new TextWidget<>(IKey.str("Player Volume & Mute")).widthRel(1f)
                            .textAlign(Alignment.Center))
                    .child(content)
                    .child(
                        new ButtonWidget<>().size(120, ROW_HEIGHT)
                            .overlay(IKey.str("Done"))
                            .onMousePressed(mouseButton -> {
                                close(true);
                                return true;
                            })));
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
                new SliderWidget()
                    .value(
                        new DoubleValue.Dynamic(
                            () -> controller.getVolume(uuid) * 100.0,
                            value -> controller.setVolume(uuid, (float) (value / 100.0))))
                    .bounds(0, 200)
                    .expanded()
                    .height(ROW_HEIGHT))
            .child(
                new TextWidget<>(IKey.dynamic(() -> Math.round(controller.getVolume(uuid) * 100) + "%"))
                    .width(VALUE_WIDTH)
                    .textAlign(Alignment.CenterRight))
            .child(
                new ToggleButton().size(MUTE_WIDTH, ROW_HEIGHT)
                    .value(new BoolValue.Dynamic(() -> controller.isMuted(uuid), muted -> {
                        controller.setMuted(uuid, muted);
                        GtnhVoice.LOG.info("[PlayerVoice] {} muted set to {}", uuid, muted);
                    }))
                    .overlay(IKey.dynamic(() -> controller.isMuted(uuid) ? "Unmute" : "Mute")));
    }

    @Override
    public void onClose() {
        super.onClose();
        Config.save();
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
