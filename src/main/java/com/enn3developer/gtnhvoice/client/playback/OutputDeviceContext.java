package com.enn3developer.gtnhvoice.client.playback;

import java.nio.IntBuffer;
import java.util.Objects;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Owns the playback thread's ALC device/context handles and the primitives that open, create, bind, tear down,
 * and close them - including HRTF attribute handling and the named-device-to-default fallbacks. Deliberately NOT
 * transactional: {@link PlaybackThread}'s startup and {@code performRebuild} orchestrate the primitives step by
 * step (the device-unchanged rebuild case reuses the live device with a brand-new context, which no single
 * open-and-bind operation could express) and commit the outcome via {@link #adopt} or abandon it via
 * {@link #clear}.
 * <p>
 * All instance state is thread-confined to the playback thread (initial name/mode are set before
 * {@code Thread.start}, everything else only ever mutates from {@code run()} or from commands run inline within
 * it) - no volatile/synchronization needed despite rebuild commands mutating the handles in place.
 */
@Lwjgl3Aware
final class OutputDeviceContext {

    // Thread-confined, see the class javadoc.
    private long device = MemoryUtil.NULL;
    private long context = MemoryUtil.NULL;
    private ALCCapabilities alcCaps;
    private String currentDeviceName;
    private Config.HrtfMode appliedHrtfMode;
    // The effective auxiliary-sends requirement the live context was created FOR (the aggregate request, not
    // the granted count), 0 when none was requested - what a late registration compares against to decide
    // whether a rebuild is needed. See createContext.
    private int contextAuxiliarySends;

    OutputDeviceContext(String initialDeviceName, Config.HrtfMode initialHrtfMode) {
        this.currentDeviceName = initialDeviceName;
        this.appliedHrtfMode = initialHrtfMode;
    }

    /** The ALC device the live context was created on - what listeners get for ALC extension checks. */
    long deviceHandle() {
        return device;
    }

    String currentDeviceName() {
        return currentDeviceName;
    }

    Config.HrtfMode appliedHrtfMode() {
        return appliedHrtfMode;
    }

    /** The auxiliary-sends requirement the live context was created for - the rebuild-decision baseline. */
    int contextAuxiliarySends() {
        return contextAuxiliarySends;
    }

    /**
     * Whether a context is currently adopted as the live output - the guard every fire-destroying and
     * error-drain site reads instead of comparing raw handles against {@code MemoryUtil.NULL}.
     */
    boolean hasLiveContext() {
        return context != MemoryUtil.NULL;
    }

    /**
     * Commits {@code newDevice}/{@code newContext} as the live output under {@code targetName} - the success
     * commit of startup and of {@code performRebuild} (whose {@code createContext} call already recorded the
     * actually-applied HRTF mode). The handles must already be open, created, and bound.
     */
    void adopt(long newDevice, long newContext, String targetName) {
        this.device = newDevice;
        this.context = newContext;
        this.currentDeviceName = targetName;
    }

    /**
     * Nulls both handles without touching AL - for the points where the underlying objects are already gone
     * (total rebuild failure, or right after {@link #closeDevice} on the old device mid-rebuild) so no field
     * dangles onto a closed handle for a later {@link #closeDevice}/{@link #teardownContext} to double-free.
     */
    void clear() {
        device = MemoryUtil.NULL;
        context = MemoryUtil.NULL;
    }

    static boolean deviceNamesEqual(String a, String b) {
        return Objects.equals(a, b);
    }

    /**
     * Opens {@code requestedDevice} by name, or the system default if {@code null}, and loads its {@link
     * ALCCapabilities} into {@link #alcCaps}. Falls back to the default device (logging a warning, never crashing)
     * if a named device fails to open or lacks {@code ALC_EXT_thread_local_context}. Returns {@code
     * MemoryUtil#NULL} only if even the default device is unusable.
     */
    long openDevice(String requestedDevice) {
        long dev = requestedDevice == null ? ALC10.alcOpenDevice((CharSequence) null)
            : ALC10.alcOpenDevice(requestedDevice);
        if (dev == MemoryUtil.NULL && requestedDevice != null) {
            GtnhVoice.LOG.warn(
                "[Playback] Failed to open requested output device '{}', falling back to default",
                requestedDevice);
            dev = ALC10.alcOpenDevice((CharSequence) null);
        }
        if (dev == MemoryUtil.NULL) {
            GtnhVoice.LOG.error("[Playback] Failed to open default playback device");
            return MemoryUtil.NULL;
        }

        ALCCapabilities caps = ALC.createCapabilities(dev);
        if (!caps.ALC_EXT_thread_local_context) {
            GtnhVoice.LOG.error("[Playback] ALC_EXT_thread_local_context is not supported by this OpenAL driver");
            ALC10.alcCloseDevice(dev);
            return MemoryUtil.NULL;
        }

        alcCaps = caps;
        return dev;
    }

    /**
     * Creates a context on {@code dev} with the HRTF attributes for {@code mode} and, when {@code
     * requestedAuxSends > 0} and the device advertises {@code ALC_EXT_EFX}, the {@code ALC_MAX_AUXILIARY_SENDS}
     * attribute - both feature-detected via {@link #alcCaps} (the LWJGL-computed equivalent of {@code
     * alcIsExtensionPresent}). HRTF degrades to AUTO (no explicit attribute - openal-soft's own default) if
     * unsupported. Sets {@link #appliedHrtfMode} to whatever was actually applied and {@link #contextAuxiliarySends}
     * to the requested count (regardless of whether EFX granted it, so a repeat request never re-triggers a
     * rebuild). Returns {@code MemoryUtil#NULL} on ALC failure.
     */
    long createContext(long dev, Config.HrtfMode mode, int requestedAuxSends) {
        boolean hrtfSupported = alcCaps.ALC_SOFT_HRTF;
        boolean wantHrtf = mode != Config.HrtfMode.AUTO && hrtfSupported;
        boolean efxSupported = alcCaps.ALC_EXT_EFX;
        boolean wantAuxSends = requestedAuxSends > 0 && efxSupported;

        Config.HrtfMode applied = mode;
        if (mode != Config.HrtfMode.AUTO && !hrtfSupported) {
            GtnhVoice.LOG.warn(
                "[Playback] ALC_SOFT_HRTF not supported by this driver; requested {} but applying AUTO",
                mode);
            applied = Config.HrtfMode.AUTO;
        }

        long ctx;
        if (!wantHrtf && !wantAuxSends) {
            ctx = ALC10.alcCreateContext(dev, (IntBuffer) null);
        } else {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // Up to two key/value attribute pairs plus the terminating 0.
                IntBuffer attribs = stack.mallocInt(5);
                if (wantHrtf) attribs.put(SOFTHRTF.ALC_HRTF_SOFT)
                    .put(mode == Config.HrtfMode.ON ? ALC10.ALC_TRUE : ALC10.ALC_FALSE);
                if (wantAuxSends) attribs.put(EXTEfx.ALC_MAX_AUXILIARY_SENDS)
                    .put(requestedAuxSends);
                attribs.put(0)
                    .flip();
                ctx = ALC10.alcCreateContext(dev, attribs);
            }
        }

        if (ctx == MemoryUtil.NULL || !AlDebug.checkAlcError(dev, "alcCreateContext")) {
            return MemoryUtil.NULL;
        }

        appliedHrtfMode = applied;
        contextAuxiliarySends = requestedAuxSends;
        GtnhVoice.LOG.info("[Playback] HRTF requested={} applied={} extensionPresent={}", mode, applied, hrtfSupported);
        if (requestedAuxSends > 0) GtnhVoice.LOG.info(
            "[Playback] Auxiliary sends requested={} granted={} extensionPresent={}",
            requestedAuxSends,
            queryMaxAuxiliarySends(dev, efxSupported),
            efxSupported);
        return ctx;
    }

    /**
     * The auxiliary sends the ALC implementation actually granted, queried straight off the device (openal-soft
     * stores the count at context creation, so this is valid before the context is made current). {@code -1}
     * when EFX is absent - there is no attribute to query and none was requested into the context.
     */
    private static int queryMaxAuxiliarySends(long dev, boolean efxSupported) {
        if (!efxSupported) return -1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer granted = stack.mallocInt(1);
            ALC10.alcGetIntegerv(dev, EXTEfx.ALC_MAX_AUXILIARY_SENDS, granted);
            return granted.get(0);
        }
    }

    /**
     * Binds {@code ctx} thread-locally and builds this thread's {@link ALCapabilities}/{@code AL10} function
     * pointers against it. {@link #alcCaps} must already correspond to the same device {@code ctx} was created on
     * - true by construction since {@link #openDevice} and {@link #createContext} are always called back-to-back
     * for the same device.
     */
    boolean bindContext(long ctx) {
        if (!EXTThreadLocalContext.alcSetThreadContext(ctx)) {
            GtnhVoice.LOG.error("[Playback] alcSetThreadContext failed");
            return false;
        }

        ALCapabilities alCaps = AL.createCapabilities(alcCaps);
        AL.setCurrentThread(alCaps);
        AL10.alDistanceModel(AL10.AL_INVERSE_DISTANCE_CLAMPED);
        return true;
    }

    /** Unbinds this thread's AL binding and destroys the live context, if any, nulling its handle. */
    void teardownContext() {
        AL.setCurrentThread(null);
        EXTThreadLocalContext.alcSetThreadContext(MemoryUtil.NULL);
        if (context != MemoryUtil.NULL) {
            ALC10.alcDestroyContext(context);
            context = MemoryUtil.NULL;
        }
    }

    void closeDevice(long dev) {
        if (dev != MemoryUtil.NULL) ALC10.alcCloseDevice(dev);
    }

    /**
     * Best-effort {@code alGetError} drain after a failed command or listener - {@link IsolatedRunner}'s injected
     * post-failure hook. The live-context state says a context should be bound, but a hostile/broken task may
     * have unbound this thread's AL binding before throwing - then {@code alGetError} itself throws (LWJGL's
     * no-capabilities {@code IllegalStateException}), and that must not escape the isolation catch block and kill
     * the pump loop it exists to protect.
     */
    void drainAlErrorAfterFailedCommand() {
        if (context == MemoryUtil.NULL) return;

        try {
            AL10.alGetError();
        } catch (Throwable ignored) {
            // Nothing to drain if the binding itself is gone; the next internal checkAlError will complain.
        }
    }
}
