package com.enn3developer.gtnhvoice.client.playback;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Dedicated thread that owns its own ALC device+context (via {@code EXT_thread_local_context}) and streams
 * 20ms/960-sample mono 16-bit PCM frames pulled off per-source queues into looping AL buffer-queue sources - one
 * positioned AL source per active {@code VoiceSource}, all sharing this one device/context.
 * <p>
 * Deliberately never calls {@code alcMakeContextCurrent} or {@link AL#setCurrentProcess}: those are process-global
 * and would stomp Minecraft's own Paulscode OpenAL context. Instead the context is bound thread-locally with
 * {@link EXTThreadLocalContext#alcSetThreadContext}, so this thread can drive its own OpenAL playback independently
 * of MC's audio engine.
 * <p>
 * AL source creation/destruction/reset is marshalled onto this thread via {@link #enqueueCommand}, since only the
 * thread holding the ALC context may call {@code AL10} functions; frame and position hand-off instead reads directly
 * from the {@link PlaybackManager}'s concurrent maps every loop iteration, avoiding per-frame command overhead.
 * <p>
 * The output device and HRTF mode can be rebuilt live via {@link #requestRebuild}, which is itself just another
 * command run through {@link #enqueueCommand} - it executes inline in this thread's own command-drain step, so it's
 * automatically serialized with every other AL call this thread makes and the pump loop is naturally quiesced for
 * its duration. The device/context are instance fields (not {@code run()} locals) specifically so that command can
 * mutate them in place; the OS thread itself never stops for a rebuild, only for real shutdown.
 * <p>
 * Every context create/destroy is announced to the {@link PlaybackLifecycleListener}s registered on the manager,
 * always through {@link #fireContextCreated}/{@link #fireContextDestroying} and always on this thread - see those
 * helpers and the listener interface for the exact pairing contract. Any future lifecycle site must go through
 * the same funnel.
 */
@Lwjgl3Aware
public class PlaybackThread extends Thread {

    private static final int SAMPLE_RATE = 48_000;
    private static final int BUFFER_POOL_SIZE = 6;
    private static final long POLL_INTERVAL_MILLIS = 5L;
    private static final long LOG_INTERVAL_MILLIS = 500L;
    private static final long COMMAND_ERROR_LOG_INTERVAL_MILLIS = 1_000L;
    private static final float REFERENCE_DISTANCE = 1.0f;
    private static final float ROLLOFF_FACTOR = 1.0f;

    // Prime-and-hysteresis tuning for AL source start, see pumpSourceChannel(). 2 is the floor: one buffer
    // playing plus one queued as cushion against decode-poller scheduling slop - the poller's frame cadence is
    // Thread.sleep-based, not phase-locked to the AL playback clock, so with a single buffer any wakeup drift
    // underruns the source. Lowered from 3 once VoiceSource gained packet-loss concealment, which keeps frames
    // flowing through genuine packet gaps instead of relying on queue depth to ride them out.
    private static final int PRIME_BUFFERS = 2;
    private static final long TAIL_FLUSH_MILLIS = 60L;

    private final PlaybackManager manager;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final Map<UUID, SourceChannel> sourceChannels = new HashMap<>();
    private final AtomicLong lastCommandErrorLogMillis = new AtomicLong();

    private volatile boolean running = true;
    private volatile boolean openedSuccessfully = false;

    // Thread-confined (only ever read/written from this thread's own run() or from commands run inline within it,
    // see the class javadoc) - no volatile/synchronization needed despite being mutated by requestRebuild's command.
    private long device = MemoryUtil.NULL;
    private long context = MemoryUtil.NULL;
    private ALCCapabilities alcCaps;
    private String currentDeviceName;
    private Config.HrtfMode appliedHrtfMode = Config.HrtfMode.AUTO;

    public PlaybackThread(PlaybackManager manager, String initialDeviceName, Config.HrtfMode initialHrtfMode) {
        super("gtnhvoice-playback");
        this.manager = manager;
        this.currentDeviceName = initialDeviceName;
        this.appliedHrtfMode = initialHrtfMode;
        setDaemon(true);
    }

    public boolean didOpenSuccessfully() {
        return openedSuccessfully;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    /**
     * Queues an AL call to run on this thread's next loop iteration. Must be used for anything touching
     * {@code AL10}/{@code ALC10} from outside this thread.
     * <p>
     * Returns {@code false} - without queuing anything - when this thread is not accepting commands: not yet
     * started, told to stop, or already dead. Commands never pile up in a queue nothing will drain. A {@code
     * true} return is acceptance, not a guarantee of execution: {@link #running} can flip false between the
     * check and the add (a concurrent {@link #shutdown} or a rebuild failing over to disabled output), in which
     * case the command was accepted but is discarded unrun when the thread exits. Each queued command runs
     * isolated - see {@link #runCommandIsolated}.
     * <p>
     * A null command throws {@link NullPointerException} regardless of lifecycle state - it's a caller bug, not
     * a rejection, and must not fail one way mid-session and another way after shutdown.
     */
    boolean enqueueCommand(Runnable command) {
        Objects.requireNonNull(command, "command");
        if (!running || !isAlive()) return false;

        commands.add(command);
        return true;
    }

    /**
     * Control-API entry point: rebuilds the output device and/or HRTF mode live. Marshalled onto this thread via
     * the same command queue every other AL lifecycle op uses - see the class javadoc.
     * <p>
     * {@link #performRebuild} handles every expected failure itself (fallback device, clean disable); a
     * Throwable escaping it is an internal bug that leaves the device/context fields desynced from reality, so
     * rather than letting {@link #runCommandIsolated} swallow it and limp on, this wrapper fails loud: log
     * unthrottled and disable output via {@link #running}.
     */
    void requestRebuild(String targetDeviceName, Config.HrtfMode targetHrtfMode) {
        enqueueCommand(() -> {
            try {
                performRebuild(targetDeviceName, targetHrtfMode);
            } catch (Throwable t) {
                GtnhVoice.LOG.error("[Playback] Rebuild threw unexpectedly; disabling output", t);
                running = false;
            }
        });
    }

    @Override
    public void run() {
        device = openDevice(currentDeviceName);
        if (device == MemoryUtil.NULL) {
            GtnhVoice.LOG.error("[Playback] Failed to open any playback device (requested or default)");
            abortStartup();
            return;
        }

        context = createContext(device, appliedHrtfMode);
        if (context == MemoryUtil.NULL) {
            closeDevice(device);
            device = MemoryUtil.NULL;
            abortStartup();
            return;
        }

        if (!bindContext(context)) {
            ALC10.alcDestroyContext(context);
            context = MemoryUtil.NULL;
            closeDevice(device);
            device = MemoryUtil.NULL;
            abortStartup();
            return;
        }

        openedSuccessfully = true;
        fireContextCreated();
        GtnhVoice.LOG.info(
            "[Playback] Playback started: {}Hz mono16, {} buffer pool per source, device={}, hrtf={}",
            SAMPLE_RATE,
            BUFFER_POOL_SIZE,
            currentDeviceName == null ? "<default>" : currentDeviceName,
            appliedHrtfMode);

        long lastLogTime = System.currentTimeMillis();

        try {
            while (running) {
                drainCommands();

                if (!running) break; // a rebuild command above may have disabled output entirely

                applyListenerSnapshot();

                long now = System.currentTimeMillis();
                for (Map.Entry<UUID, SourceChannel> entry : sourceChannels.entrySet()) {
                    pumpSourceChannel(entry.getKey(), entry.getValue(), now);
                }

                if (now - lastLogTime >= LOG_INTERVAL_MILLIS) {
                    logChannelsThrottled();
                    lastLogTime = now;
                }

                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } finally {
            // Guarded so destroying never double-fires: after a total rebuild failure the context field is
            // already NULL (performRebuild nulled it, having fired destroying for the old context at its top),
            // so this only announces a context whose destroying hasn't fired yet.
            if (context != MemoryUtil.NULL) fireContextDestroying();
            teardownAlSources();
            teardownContext();
            closeDevice(device);
            device = MemoryUtil.NULL;
            openedSuccessfully = false;
            // Drop anything that won the enqueueCommand acceptance race against shutdown (see its javadoc) -
            // nothing will ever drain this queue again.
            commands.clear();

            GtnhVoice.LOG.info("[Playback] Playback stopped and device closed");
        }
    }

    /**
     * Startup failed before the pump loop ever ran, so run()'s try/finally (and its queue clear) is never
     * reached: stop accepting commands and drop any that were accepted during the open-device window, otherwise
     * they'd sit forever in a queue nothing drains - exactly what {@link #enqueueCommand}'s contract forbids.
     */
    private void abortStartup() {
        running = false;
        commands.clear();
    }

    /**
     * Runs on this thread only, at the top of every loop iteration: drains and executes every queued command in
     * submission order, each one isolated via {@link #runCommandIsolated} so a single throwing command can't
     * unwind the pump loop and kill playback for every source.
     */
    private void drainCommands() {
        Runnable command;
        while ((command = commands.poll()) != null) {
            runCommandIsolated(command);
        }
    }

    /**
     * Runs {@code command}, catching any {@link Throwable} it throws - deliberately including errors, since an
     * addon command whose class is missing lwjgl3ify's {@code @Lwjgl3Aware} dies with {@code
     * NoClassDefFoundError}, and that must not tear down playback. Failures are logged at error level, throttled
     * to one per {@value #COMMAND_ERROR_LOG_INTERVAL_MILLIS}ms. After a failure with the context bound, the AL
     * error state is drained so a half-executed command's dangling error isn't misattributed to this thread's
     * next internal {@link #checkAlError}.
     * <p>
     * Package-private only so the isolation contract is unit-testable without an AL device; every real call
     * happens on this thread from {@link #drainCommands}.
     */
    void runCommandIsolated(Runnable command) {
        runIsolated(command, "Queued command");
    }

    /**
     * Shared isolation wrapper behind both {@link #runCommandIsolated} and listener dispatch
     * ({@link #fireContextCreated}/{@link #fireContextDestroying}): runs {@code task}, catching any
     * {@link Throwable} with a throttled error log naming {@code what}, then drains the AL error state so a
     * half-executed task's dangling error isn't misattributed to this thread's next internal
     * {@link #checkAlError}.
     */
    private void runIsolated(Runnable task, String what) {
        try {
            task.run();
        } catch (Throwable t) {
            if (LogThrottle.shouldLog(lastCommandErrorLogMillis, COMMAND_ERROR_LOG_INTERVAL_MILLIS)) {
                GtnhVoice.LOG.error("[Playback] {} threw on the playback thread", what, t);
            }
            drainAlErrorAfterFailedCommand();
        }
    }

    /**
     * The single funnel announcing that a context has become the live output - called from run()'s startup path
     * and {@link #performRebuild}'s success path (which covers the default-device fallback too), always with the
     * new context bound and current and before any AL sources exist on it. Passes {@link #device} so listeners
     * can run ALC extension checks. Each listener is dispatched isolated via {@link #runIsolated} - a broken one
     * can't kill the pump loop or starve the others.
     * <p>
     * Package-private only so listener dispatch is unit-testable without an AL device; every real call happens
     * on this thread.
     */
    void fireContextCreated() {
        for (PlaybackLifecycleListener listener : manager.lifecycleListenersView()) {
            runIsolated(() -> listener.contextCreated(device), "Lifecycle listener contextCreated");
        }
    }

    /**
     * The single funnel announcing that the live context is about to die - called before ANY AL teardown at the
     * top of {@link #performRebuild} and in run()'s finally, both guarded on a live {@link #context} so a
     * never-announced (or already-announced) context can never fire destroying. The context is still bound and
     * every AL source still exists when listeners run. Isolation and visibility rationale as
     * {@link #fireContextCreated}.
     */
    void fireContextDestroying() {
        for (PlaybackLifecycleListener listener : manager.lifecycleListenersView()) {
            runIsolated(listener::contextDestroying, "Lifecycle listener contextDestroying");
        }
    }

    /**
     * Best-effort {@code alGetError} drain after a failed command. The {@link #context} field says a context
     * should be bound, but a hostile/broken command may have unbound this thread's AL binding before throwing -
     * then {@code alGetError} itself throws (LWJGL's no-capabilities {@code IllegalStateException}), and that
     * must not escape {@link #runCommandIsolated}'s catch block and kill the pump loop this isolation exists to
     * protect.
     */
    private void drainAlErrorAfterFailedCommand() {
        if (context == MemoryUtil.NULL) return;

        try {
            AL10.alGetError();
        } catch (Throwable ignored) {
            // Nothing to drain if the binding itself is gone; the next internal checkAlError will complain.
        }
    }

    /**
     * Runs on this thread only (queued via {@link #requestRebuild}): the ordered output rebuild - stop/destroy
     * every AL source (b), destroy the old context and, only if the device selection actually changed, close the
     * old device and open the new one (c), create the new context with the requested HRTF attributes (d), and bind
     * it (e is implicit: this thread never stopped, so nothing needs restarting - the pump loop just resumes next
     * iteration). {@code VoiceSource}s are never touched here; their AL sources reappear lazily the next time
     * {@code PlaybackManager#createSource} runs for them (see {@code VoiceSource#handleAudio}).
     * <p>
     * On failure, falls back once to the default device with AUTO HRTF; if that also fails, output is cleanly
     * disabled (this thread exits, and every {@link PlaybackManager} entry point already no-ops once
     * {@link PlaybackManager#isPlaying()} is false) rather than left in a half-torn-down state.
     */
    private void performRebuild(String targetDeviceName, Config.HrtfMode targetHrtfMode) {
        GtnhVoice.LOG.info(
            "[Playback] Rebuild starting: device={} hrtf={}",
            targetDeviceName == null ? "<default>" : targetDeviceName,
            targetHrtfMode);

        // Announce before ANY AL teardown, while the old context is still bound and its sources still exist -
        // that's the listener contract. Guarded: a rebuild queued behind one that already failed totally runs
        // with no live context, and a context that was never announced must never get a destroying.
        if (context != MemoryUtil.NULL) fireContextDestroying();
        teardownAlSources();
        teardownContext();

        boolean deviceChanging = !deviceNamesEqual(currentDeviceName, targetDeviceName);
        long newDevice = device;
        if (deviceChanging) {
            closeDevice(device);
            device = MemoryUtil.NULL;
            newDevice = openDevice(targetDeviceName);
        }

        long newContext = newDevice == MemoryUtil.NULL ? MemoryUtil.NULL : createContext(newDevice, targetHrtfMode);
        boolean bound = newContext != MemoryUtil.NULL && bindContext(newContext);

        if (!bound) {
            GtnhVoice.LOG.error(
                "[Playback] Rebuild to device={} hrtf={} failed, falling back to default device/AUTO",
                targetDeviceName == null ? "<default>" : targetDeviceName,
                targetHrtfMode);
            if (newContext != MemoryUtil.NULL) ALC10.alcDestroyContext(newContext);
            if (newDevice != MemoryUtil.NULL) closeDevice(newDevice);

            newDevice = openDevice(null);
            newContext = newDevice == MemoryUtil.NULL ? MemoryUtil.NULL
                : createContext(newDevice, Config.HrtfMode.AUTO);
            bound = newContext != MemoryUtil.NULL && bindContext(newContext);

            if (!bound) {
                GtnhVoice.LOG.error("[Playback] Rebuild failed entirely; disabling output until next reconnect");
                if (newContext != MemoryUtil.NULL) ALC10.alcDestroyContext(newContext);
                if (newDevice != MemoryUtil.NULL) closeDevice(newDevice);
                device = MemoryUtil.NULL;
                context = MemoryUtil.NULL;
                running = false; // clean shutdown on the next loop check; run()'s finally is now a no-op teardown
                return;
            }

            targetDeviceName = null;
            targetHrtfMode = Config.HrtfMode.AUTO;
        }

        device = newDevice;
        context = newContext;
        currentDeviceName = targetDeviceName;
        // appliedHrtfMode already set by createContext(), which may have degraded targetHrtfMode to AUTO.

        // Single created-fire point for both the target and the fallback outcome - either way the new context
        // is bound and live by now, and no AL sources exist on it yet (they reappear lazily via commands).
        fireContextCreated();

        GtnhVoice.LOG.info(
            "[Playback] Rebuild complete: device={} hrtfRequested={} hrtfApplied={}",
            currentDeviceName == null ? "<default>" : currentDeviceName,
            targetHrtfMode,
            appliedHrtfMode);
    }

    private static boolean deviceNamesEqual(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Opens {@code requestedDevice} by name, or the system default if {@code null}, and loads its {@link
     * ALCCapabilities} into {@link #alcCaps}. Falls back to the default device (logging a warning, never crashing)
     * if a named device fails to open or lacks {@code ALC_EXT_thread_local_context}. Returns {@code
     * MemoryUtil#NULL} only if even the default device is unusable.
     */
    private long openDevice(String requestedDevice) {
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
     * Creates a context on {@code dev} with the HRTF attributes for {@code mode}, feature-detecting {@code
     * ALC_SOFT_HRTF} via {@link #alcCaps} (the LWJGL-computed equivalent of {@code alcIsExtensionPresent}) and
     * degrading to AUTO (no explicit attribute - openal-soft's own default) if unsupported. Sets {@link
     * #appliedHrtfMode} to whatever was actually applied. Returns {@code MemoryUtil#NULL} on ALC failure.
     */
    private long createContext(long dev, Config.HrtfMode mode) {
        boolean hrtfSupported = alcCaps.ALC_SOFT_HRTF;
        Config.HrtfMode applied = mode;
        long ctx;

        if (mode != Config.HrtfMode.AUTO && hrtfSupported) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer attribs = stack.mallocInt(3);
                attribs.put(SOFTHRTF.ALC_HRTF_SOFT)
                    .put(mode == Config.HrtfMode.ON ? ALC10.ALC_TRUE : ALC10.ALC_FALSE)
                    .put(0)
                    .flip();
                ctx = ALC10.alcCreateContext(dev, attribs);
            }
        } else {
            if (mode != Config.HrtfMode.AUTO) {
                GtnhVoice.LOG.warn(
                    "[Playback] ALC_SOFT_HRTF not supported by this driver; requested {} but applying AUTO",
                    mode);
                applied = Config.HrtfMode.AUTO;
            }
            ctx = ALC10.alcCreateContext(dev, (IntBuffer) null);
        }

        if (ctx == MemoryUtil.NULL || !checkAlcError(dev, "alcCreateContext")) {
            return MemoryUtil.NULL;
        }

        appliedHrtfMode = applied;
        GtnhVoice.LOG.info("[Playback] HRTF requested={} applied={} extensionPresent={}", mode, applied, hrtfSupported);
        return ctx;
    }

    /**
     * Binds {@code ctx} thread-locally and builds this thread's {@link ALCapabilities}/{@code AL10} function
     * pointers against it. {@link #alcCaps} must already correspond to the same device {@code ctx} was created on
     * - true by construction since {@link #openDevice} and {@link #createContext} are always called back-to-back
     * for the same device.
     */
    private boolean bindContext(long ctx) {
        if (!EXTThreadLocalContext.alcSetThreadContext(ctx)) {
            GtnhVoice.LOG.error("[Playback] alcSetThreadContext failed");
            return false;
        }

        ALCapabilities alCaps = AL.createCapabilities(alcCaps);
        AL.setCurrentThread(alCaps);
        AL10.alDistanceModel(AL10.AL_INVERSE_DISTANCE_CLAMPED);
        return true;
    }

    /**
     * Stops, flushes, and deletes every active {@link SourceChannel}'s AL source and buffers (step b of a
     * rebuild), leaving {@link #sourceChannels} empty. Never touches the owning {@code VoiceSource}s - their
     * decoder/jitter state lives entirely outside this class.
     */
    private void teardownAlSources() {
        for (Iterator<SourceChannel> it = sourceChannels.values()
            .iterator(); it.hasNext();) {
            SourceChannel channel = it.next();
            stopAndFlush(channel);
            AL10.alDeleteSources(channel.alSource);
            AL10.alDeleteBuffers(channel.bufferIds);
            it.remove();
        }
        checkAlError("teardownAlSources");
    }

    private void teardownContext() {
        AL.setCurrentThread(null);
        EXTThreadLocalContext.alcSetThreadContext(MemoryUtil.NULL);
        if (context != MemoryUtil.NULL) {
            ALC10.alcDestroyContext(context);
            context = MemoryUtil.NULL;
        }
    }

    private void closeDevice(long dev) {
        if (dev != MemoryUtil.NULL) ALC10.alcCloseDevice(dev);
    }

    private void pumpSourceChannel(UUID sourceId, SourceChannel channel, long now) {
        int processed = AL10.alGetSourcei(channel.alSource, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            channel.freeBuffers.add(AL10.alSourceUnqueueBuffers(channel.alSource));
        }
        checkAlError("alSourceUnqueueBuffers");

        // OpenAL marks a STOPPED source's entire queue as processed instantly, even buffers that never played -
        // so a source left STOPPED must be rewound to INITIAL before any new buffer is queued onto it, or the
        // next tick's unqueue-processed step above will silently discard it before it ever primes.
        if (AL10.alGetSourcei(channel.alSource, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
            int leftoverQueued = AL10.alGetSourcei(channel.alSource, AL10.AL_BUFFERS_QUEUED);
            if (leftoverQueued > 0) {
                GtnhVoice.LOG.warn(
                    "[Playback] sourceId={} found STOPPED with {} buffers still queued after unqueue - a reset path was missed",
                    sourceId,
                    leftoverQueued);
            }
            channel.underruns++;
            AL10.alSourceRewind(channel.alSource);
            checkAlError("alSourceRewind");
        }

        Boolean positionalMode = manager.positionalModesView()
            .get(sourceId);
        boolean positional = positionalMode == null || positionalMode;
        if (positional != channel.positional) {
            applySourceMode(channel, sourceId, positional);
        }

        if (channel.positional) {
            double[] position = manager.positionsView()
                .get(sourceId);
            if (position != null) {
                AL10.alSource3f(
                    channel.alSource,
                    AL10.AL_POSITION,
                    (float) position[0],
                    (float) position[1],
                    (float) position[2]);
            }
        }

        while (!channel.freeBuffers.isEmpty()) {
            short[] frame = channel.frameQueue.poll();
            if (frame == null) break;

            int bufferId = channel.freeBuffers.poll();
            AL10.alBufferData(bufferId, AL10.AL_FORMAT_MONO16, frame, SAMPLE_RATE);
            AL10.alSourceQueueBuffers(channel.alSource, bufferId);
            checkAlError("alBufferData/alSourceQueueBuffers");
            channel.framesQueued++;
            channel.lastFrameQueuedAtMillis = now;
        }

        int queued = AL10.alGetSourcei(channel.alSource, AL10.AL_BUFFERS_QUEUED);
        int state = AL10.alGetSourcei(channel.alSource, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_PLAYING || queued == 0) return;

        boolean primed = queued >= PRIME_BUFFERS;
        boolean tailFlush = !primed && (now - channel.lastFrameQueuedAtMillis) > TAIL_FLUSH_MILLIS;
        if (!primed && !tailFlush) return;

        if (tailFlush) {
            GtnhVoice.LOG.info(
                "[Playback] tail flush sourceId={} queued={} state={}",
                sourceId,
                queued,
                alSourceStateToString(state));
        }
        AL10.alSourcePlay(channel.alSource);
        checkAlError("alSourcePlay");
    }

    /**
     * Runs on this thread only, from {@link #pumpSourceChannel}'s per-iteration mode check: flips one AL source
     * between positional (world-positioned, distance-attenuated - exactly how {@link #createSourceChannel} builds
     * it) and flat (listener-relative at the origin with zero rolloff, so it plays at full gain regardless of
     * where anyone stands). The desired mode arrives with every audio packet, so a speaker switching groups
     * mid-stream flips their existing source in place - no teardown, and this only executes on an actual change.
     * On a flip back to positional the pump re-applies the source's world position in the same iteration.
     */
    private void applySourceMode(SourceChannel channel, UUID sourceId, boolean positional) {
        AL10.alSourcei(channel.alSource, AL10.AL_SOURCE_RELATIVE, positional ? AL10.AL_FALSE : AL10.AL_TRUE);
        AL10.alSourcef(channel.alSource, AL10.AL_ROLLOFF_FACTOR, positional ? ROLLOFF_FACTOR : 0f);
        if (!positional) {
            AL10.alSource3f(channel.alSource, AL10.AL_POSITION, 0f, 0f, 0f);
        }
        checkAlError("applySourceMode");

        channel.positional = positional;
        GtnhVoice.LOG.info("[Playback] Source mode switched for sourceId={} positional={}", sourceId, positional);
    }

    private void applyListenerSnapshot() {
        ListenerSnapshot snapshot = manager.currentListenerSnapshot();
        AL10.alListener3f(AL10.AL_POSITION, (float) snapshot.x(), (float) snapshot.y(), (float) snapshot.z());
        AL10.alListenerfv(
            AL10.AL_ORIENTATION,
            new float[] { snapshot.lookX(), snapshot.lookY(), snapshot.lookZ(), 0f, 1f, 0f });
    }

    /**
     * Runs on this thread only (queued via {@link #enqueueCommand}): allocates a positioned AL source + buffer pool
     * for a newly seen {@code sourceId}. No-op if one already exists - including right after a rebuild wiped
     * {@link #sourceChannels}, in which case this is exactly what lazily recreates it.
     */
    void createSourceChannel(UUID sourceId, BlockingQueue<short[]> frameQueue, int distance, float gain) {
        if (sourceChannels.containsKey(sourceId)) return;

        int source = AL10.alGenSources();
        if (!checkAlError("alGenSources")) return;

        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, REFERENCE_DISTANCE);
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, distance);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, ROLLOFF_FACTOR);
        AL10.alSourcef(source, AL10.AL_GAIN, gain);

        int[] bufferIds = new int[BUFFER_POOL_SIZE];
        AL10.alGenBuffers(bufferIds);
        if (!checkAlError("alGenBuffers")) {
            AL10.alDeleteSources(source);
            return;
        }

        Deque<Integer> freeBuffers = new ArrayDeque<>(BUFFER_POOL_SIZE);
        for (int bufferId : bufferIds) freeBuffers.add(bufferId);

        sourceChannels.put(sourceId, new SourceChannel(source, bufferIds, freeBuffers, frameQueue));
        GtnhVoice.LOG.info("[Playback] AL source created for sourceId={} gain={}", sourceId, gain);
    }

    /**
     * Runs on this thread only (queued via {@link PlaybackManager#setGain}): applies a live {@code AL_GAIN} update
     * to {@code sourceId}'s AL source. No-op if it doesn't currently have one - the value is still recorded in
     * {@link PlaybackManager}'s gain map and will be picked up whenever {@link #createSourceChannel} next runs for
     * it (e.g. the player starts speaking, or a rebuild recreates the channel).
     */
    void applyGain(UUID sourceId, float gain) {
        SourceChannel channel = sourceChannels.get(sourceId);
        if (channel == null) return;

        AL10.alSourcef(channel.alSource, AL10.AL_GAIN, gain);
        checkAlError("applyGain");
        GtnhVoice.LOG.info("[Playback] Gain applied for sourceId={} gain={}", sourceId, gain);
    }

    /**
     * Runs on this thread only: fully stops, unqueues, and deletes {@code sourceId}'s AL source and buffers, freeing
     * the handles. Used when the speaker disconnects.
     */
    void destroySourceChannel(UUID sourceId) {
        SourceChannel channel = sourceChannels.remove(sourceId);
        if (channel == null) return;

        stopAndFlush(channel);
        AL10.alDeleteSources(channel.alSource);
        AL10.alDeleteBuffers(channel.bufferIds);
        checkAlError("destroySourceChannel");
        GtnhVoice.LOG.info("[Playback] AL source destroyed for sourceId={}", sourceId);
    }

    /**
     * Runs on this thread only: stops {@code sourceId}'s AL source and returns its queued buffers to the free pool,
     * but keeps the AL source handle alive. Used on speech-segment inactivity reset.
     */
    void resetSourceChannel(UUID sourceId) {
        SourceChannel channel = sourceChannels.get(sourceId);
        if (channel == null) return;

        stopAndFlush(channel);
        GtnhVoice.LOG.info("[Playback] AL source reset for sourceId={}", sourceId);
    }

    private void stopAndFlush(SourceChannel channel) {
        AL10.alSourceStop(channel.alSource);
        checkAlError("alSourceStop");

        int queued = AL10.alGetSourcei(channel.alSource, AL10.AL_BUFFERS_QUEUED);
        for (int i = 0; i < queued; i++) {
            channel.freeBuffers.add(AL10.alSourceUnqueueBuffers(channel.alSource));
        }
        checkAlError("stopAndFlush unqueue");

        // Rewind STOPPED -> INITIAL so a subsequent re-prime doesn't leave freshly queued buffers sitting on a
        // STOPPED source, where OpenAL would mark them processed (and thus silently discarded) before they play.
        AL10.alSourceRewind(channel.alSource);
        checkAlError("alSourceRewind");

        channel.lastFrameQueuedAtMillis = 0L;
    }

    private void logChannelsThrottled() {
        for (Map.Entry<UUID, SourceChannel> entry : sourceChannels.entrySet()) {
            SourceChannel channel = entry.getValue();
            int state = AL10.alGetSourcei(channel.alSource, AL10.AL_SOURCE_STATE);
            int queuedAl = AL10.alGetSourcei(channel.alSource, AL10.AL_BUFFERS_QUEUED);
            GtnhVoice.LOG.info(
                "[Playback] sourceId={} framesQueued={} underruns={} sourceState={} queuedAl={}",
                entry.getKey(),
                channel.framesQueued,
                channel.underruns,
                alSourceStateToString(state),
                queuedAl);
        }
    }

    private boolean checkAlError(String context) {
        int error = AL10.alGetError();
        if (error == AL10.AL_NO_ERROR) return true;

        GtnhVoice.LOG.error("[Playback] AL error after {}: {}", context, alErrorToString(error));
        return false;
    }

    private boolean checkAlcError(long device, String context) {
        int error = ALC10.alcGetError(device);
        if (error == ALC10.ALC_NO_ERROR) return true;

        GtnhVoice.LOG.error("[Playback] ALC error after {}: {}", context, error);
        return false;
    }

    private static String alErrorToString(int error) {
        return switch (error) {
            case AL10.AL_INVALID_NAME -> "AL_INVALID_NAME";
            case AL10.AL_INVALID_ENUM -> "AL_INVALID_ENUM";
            case AL10.AL_INVALID_VALUE -> "AL_INVALID_VALUE";
            case AL10.AL_INVALID_OPERATION -> "AL_INVALID_OPERATION";
            case AL10.AL_OUT_OF_MEMORY -> "AL_OUT_OF_MEMORY";
            default -> "unknown error code " + error;
        };
    }

    private static String alSourceStateToString(int state) {
        return switch (state) {
            case AL10.AL_PLAYING -> "PLAYING";
            case AL10.AL_PAUSED -> "PAUSED";
            case AL10.AL_STOPPED -> "STOPPED";
            case AL10.AL_INITIAL -> "INITIAL";
            default -> "unknown state " + state;
        };
    }

    /**
     * Per-source AL state: one positioned source, its buffer pool, and the frame queue it pulls from. Only ever
     * touched from {@link PlaybackThread}'s own thread.
     */
    private static final class SourceChannel {

        final int alSource;
        final int[] bufferIds;
        final Deque<Integer> freeBuffers;
        final BlockingQueue<short[]> frameQueue;

        long framesQueued;
        long underruns;
        long lastFrameQueuedAtMillis;
        // Freshly created channels are positional - that's exactly how createSourceChannel configures the AL
        // source (also after a rebuild recreates it; the pump's per-iteration mode check re-flattens it if the
        // manager's map says so). Flipped only by applySourceMode on this thread.
        boolean positional = true;

        SourceChannel(int alSource, int[] bufferIds, Deque<Integer> freeBuffers, BlockingQueue<short[]> frameQueue) {
            this.alSource = alSource;
            this.bufferIds = bufferIds;
            this.freeBuffers = freeBuffers;
            this.frameQueue = frameQueue;
        }
    }
}
