package com.enn3developer.gtnhvoice.client.audio;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * DEBUG-ONLY, TEMPORARY: exercises {@link AudioDeviceController} via three unbound keybinds so the hotswap/HRTF
 * rebuild logic (Task 1) can be tested without the settings GUI (Task 2). Cycles input device, output device, and
 * HRTF mode (AUTO -&gt; ON -&gt; OFF). Task 2 replaces this whole class with GUI controls calling the same
 * {@link AudioDeviceController} API - nothing else in the codebase depends on it, so it can be deleted outright
 * once the GUI lands.
 * <p>
 * Must be public: FML's ASM event bus subscriber scanning throws {@link IllegalAccessError} on non-public listener
 * classes under lwjgl3ify.
 */
public class AudioDeviceDebugDriver {

    private static final String CATEGORY = "key.categories.gtnhvoice";

    private final KeyBinding cycleInputDeviceKey = new KeyBinding(
        "key.gtnhvoice.debugCycleInputDevice",
        Keyboard.KEY_NONE,
        CATEGORY);
    private final KeyBinding cycleOutputDeviceKey = new KeyBinding(
        "key.gtnhvoice.debugCycleOutputDevice",
        Keyboard.KEY_NONE,
        CATEGORY);
    private final KeyBinding cycleHrtfKey = new KeyBinding("key.gtnhvoice.debugCycleHrtf", Keyboard.KEY_NONE, CATEGORY);

    private boolean inputKeyWasDown;
    private boolean outputKeyWasDown;
    private boolean hrtfKeyWasDown;

    public void register() {
        ClientRegistry.registerKeyBinding(cycleInputDeviceKey);
        ClientRegistry.registerKeyBinding(cycleOutputDeviceKey);
        ClientRegistry.registerKeyBinding(cycleHrtfKey);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        GtnhVoice.LOG.info("[AudioDeviceDebug] Debug audio-device driver registered (throwaway, pre-GUI test rig)");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        boolean inputDown = cycleInputDeviceKey.getIsKeyPressed();
        if (inputDown && !inputKeyWasDown) cycleInputDevice();
        inputKeyWasDown = inputDown;

        boolean outputDown = cycleOutputDeviceKey.getIsKeyPressed();
        if (outputDown && !outputKeyWasDown) cycleOutputDevice();
        outputKeyWasDown = outputDown;

        boolean hrtfDown = cycleHrtfKey.getIsKeyPressed();
        if (hrtfDown && !hrtfKeyWasDown) cycleHrtf();
        hrtfKeyWasDown = hrtfDown;
    }

    private void cycleInputDevice() {
        AudioDeviceController controller = AudioDeviceController.getInstance();
        List<String> options = withDefaultOption(controller.listInputDevices());
        String current = controller.getInputDevice();
        String next = nextOption(options, current);

        GtnhVoice.LOG.info(
            "[AudioDeviceDebug] Cycling input device: '{}' -> '{}'",
            current == null ? "<default>" : current,
            next == null ? "<default>" : next);
        controller.setInputDevice(next);
    }

    private void cycleOutputDevice() {
        AudioDeviceController controller = AudioDeviceController.getInstance();
        List<String> options = withDefaultOption(controller.listOutputDevices());
        String current = controller.getOutputDevice();
        String next = nextOption(options, current);

        GtnhVoice.LOG.info(
            "[AudioDeviceDebug] Cycling output device: '{}' -> '{}'",
            current == null ? "<default>" : current,
            next == null ? "<default>" : next);
        controller.setOutputDevice(next);
    }

    private void cycleHrtf() {
        AudioDeviceController controller = AudioDeviceController.getInstance();
        Config.HrtfMode[] modes = Config.HrtfMode.values();
        Config.HrtfMode current = controller.getHrtfMode();
        Config.HrtfMode next = modes[(current.ordinal() + 1) % modes.length];

        GtnhVoice.LOG.info("[AudioDeviceDebug] Cycling HRTF mode: {} -> {}", current, next);
        controller.setHrtfMode(next);
    }

    /**
     * {@code null} (system default) followed by every enumerated device name, so cycling always includes an
     * explicit "go back to default" step even if enumeration is empty or fails.
     */
    private List<String> withDefaultOption(List<String> devices) {
        List<String> options = new ArrayList<>(devices.size() + 1);
        options.add(null);
        options.addAll(devices);
        return options;
    }

    private String nextOption(List<String> options, String current) {
        int index = options.indexOf(current);
        int nextIndex = (index < 0 ? 0 : index + 1) % options.size();
        return options.get(nextIndex);
    }
}
