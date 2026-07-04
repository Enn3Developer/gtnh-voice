package com.enn3developer.gtnhvoice.client.audio;

import java.util.Collections;
import java.util.List;

import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALUtil;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Static ALC device enumeration, shared by the capture and playback sides so both list devices the same way.
 * Safe to call from any thread: device enumeration is a query against no particular device/context (mirrors
 * {@code CaptureThread}'s existing capture-device enumeration, which already runs this way).
 */
@Lwjgl3Aware
public final class AudioDeviceUtil {

    private AudioDeviceUtil() {}

    public static List<String> listInputDevices() {
        List<String> devices = ALUtil.getStringList(0L, ALC11.ALC_CAPTURE_DEVICE_SPECIFIER);
        return devices == null ? Collections.emptyList() : devices;
    }

    /**
     * Output devices via {@code ALC_ENUMERATE_ALL_EXT} (lists every physical device, not just OpenAL-preferred
     * ones), falling back to the plain {@code ALC_DEVICE_SPECIFIER} list if that extension isn't present.
     */
    public static List<String> listOutputDevices() {
        List<String> devices = ALUtil.getStringList(0L, ALC11.ALC_ALL_DEVICES_SPECIFIER);
        if (devices == null || devices.isEmpty()) {
            devices = ALUtil.getStringList(0L, ALC10.ALC_DEVICE_SPECIFIER);
        }
        return devices == null ? Collections.emptyList() : devices;
    }

    public static String defaultInputDevice() {
        return ALC10.alcGetString(0L, ALC11.ALC_CAPTURE_DEFAULT_DEVICE_SPECIFIER);
    }

    public static String defaultOutputDevice() {
        String name = ALC10.alcGetString(0L, ALC11.ALC_DEFAULT_ALL_DEVICES_SPECIFIER);
        return name != null ? name : ALC10.alcGetString(0L, ALC10.ALC_DEFAULT_DEVICE_SPECIFIER);
    }
}
