package com.enn3developer.gtnhvoice.client.playback;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.system.MemoryUtil;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.GtnhVoice;

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
 * This class is the orchestrator; the real work lives in thread-confined collaborators it owns: the ALC
 * device/context handles and their open/create/bind/teardown primitives in {@link OutputDeviceContext}, the
 * per-source AL channels and the pump in {@link SourceChannelPool}, lifecycle event dispatch in
 * {@link LifecycleEventDispatcher}, and Throwable isolation (one shared instance - and thus one shared error-log
 * throttle - behind both queued commands and listener dispatch) in {@link IsolatedRunner}. What stays here: the
 * command queue, run()'s startup/pump/teardown skeleton, and {@link #performRebuild} orchestration.
 * <p>
 * AL source creation/destruction/reset is marshalled onto this thread via {@link #enqueueCommand}, since only the
 * thread holding the ALC context may call {@code AL10} functions; frame and position hand-off instead reads directly
 * from the {@link PlaybackManager}'s concurrent maps every loop iteration, avoiding per-frame command overhead.
 * <p>
 * The output device and HRTF mode can be rebuilt live via {@link #requestRebuild}, which is itself just another
 * command run through {@link #enqueueCommand} - it executes inline in this thread's own command-drain step, so it's
 * automatically serialized with every other AL call this thread makes and the pump loop is naturally quiesced for
 * its duration. The device/context live on the {@link OutputDeviceContext} instance field (not {@code run()}
 * locals) specifically so that command can mutate them in place; the OS thread itself never stops for a rebuild,
 * only for real shutdown.
 * <p>
 * Every context create/destroy and AL source create/destroy is announced to the
 * {@link PlaybackLifecycleListener}s registered on the manager, always through the
 * {@link LifecycleEventDispatcher} funnel and always on this thread - see that class and the listener interface
 * for the exact pairing and ordering contracts. Any future lifecycle site must go through the same funnel.
 */
@Lwjgl3Aware
public class PlaybackThread extends Thread {

    private static final long POLL_INTERVAL_MILLIS = 5L;
    private static final long LOG_INTERVAL_MILLIS = 500L;

    private final PlaybackManager manager;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();

    private final OutputDeviceContext deviceContext;
    private final IsolatedRunner isolatedRunner;
    private final LifecycleEventDispatcher dispatcher;
    private final SourceChannelPool channelPool;

    private volatile boolean running = true;
    private volatile boolean openedSuccessfully = false;

    public PlaybackThread(PlaybackManager manager, String initialDeviceName, Config.HrtfMode initialHrtfMode) {
        super("gtnhvoice-playback");
        this.manager = manager;
        this.deviceContext = new OutputDeviceContext(initialDeviceName, initialHrtfMode);
        this.isolatedRunner = new IsolatedRunner(deviceContext::drainAlErrorAfterFailedCommand);
        this.dispatcher = new LifecycleEventDispatcher(manager, isolatedRunner);
        this.channelPool = new SourceChannelPool(manager, dispatcher);
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
     * Throwable escaping it is an internal bug that leaves the device/context state desynced from reality, so
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
        long device = deviceContext.openDevice(deviceContext.currentDeviceName());
        if (device == MemoryUtil.NULL) {
            GtnhVoice.LOG.error("[Playback] Failed to open any playback device (requested or default)");
            abortStartup();
            return;
        }

        long context = deviceContext.createContext(device, deviceContext.appliedHrtfMode());
        if (context == MemoryUtil.NULL) {
            deviceContext.closeDevice(device);
            abortStartup();
            return;
        }

        if (!deviceContext.bindContext(context)) {
            ALC10.alcDestroyContext(context);
            deviceContext.closeDevice(device);
            abortStartup();
            return;
        }

        deviceContext.adopt(device, context, deviceContext.currentDeviceName());
        openedSuccessfully = true;
        dispatcher.fireContextCreated(deviceContext.deviceHandle());
        GtnhVoice.LOG.info(
            "[Playback] Playback started: {}Hz mono16, {} buffer pool per source, device={}, hrtf={}",
            SourceChannelPool.SAMPLE_RATE,
            SourceChannelPool.BUFFER_POOL_SIZE,
            deviceContext.currentDeviceName() == null ? "<default>" : deviceContext.currentDeviceName(),
            deviceContext.appliedHrtfMode());

        long lastLogTime = System.currentTimeMillis();

        try {
            while (running) {
                drainCommands();

                if (!running) break; // a rebuild command above may have disabled output entirely

                applyListenerSnapshot();

                // The dispatcher documents it: the CALLER owns audioTick's fires-only-with-sources condition.
                if (!channelPool.isEmpty()) dispatcher.fireAudioTick();

                long now = System.currentTimeMillis();
                for (Map.Entry<UUID, SourceChannelPool.SourceChannel> entry : channelPool.channelsView()
                    .entrySet()) {
                    channelPool.pumpSourceChannel(entry.getKey(), entry.getValue(), now);
                }

                if (now - lastLogTime >= LOG_INTERVAL_MILLIS) {
                    channelPool.logChannelsThrottled();
                    lastLogTime = now;
                }

                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } finally {
            // Guarded so destroying never double-fires: after a total rebuild failure the context is already
            // gone (performRebuild cleared it, having announced the old context's teardown at its top), so this
            // only announces a context whose destroying hasn't fired yet.
            if (deviceContext.hasLiveContext()) dispatcher.fireContextTeardown(channelPool.channelsView());
            channelPool.teardownAlSources();
            deviceContext.teardownContext();
            deviceContext.closeDevice(deviceContext.deviceHandle());
            deviceContext.clear();
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
     * Runs {@code command} through the shared {@link IsolatedRunner} - see that class for the full isolation
     * contract (catch-Throwable, throttled error log, AL error drain).
     * <p>
     * Package-private only so the isolation contract is unit-testable without an AL device; every real call
     * happens on this thread from {@link #drainCommands}.
     */
    void runCommandIsolated(Runnable command) {
        isolatedRunner.run(command, "Queued command");
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

        // Announce (all sources, then the context) before ANY AL teardown, while the old context is still bound
        // and its sources still exist - that's the listener contract. Guarded: a rebuild queued behind one that
        // already failed totally runs with no live context, and a context that was never announced must never get
        // a destroying.
        if (deviceContext.hasLiveContext()) dispatcher.fireContextTeardown(channelPool.channelsView());
        channelPool.teardownAlSources();
        deviceContext.teardownContext();

        boolean deviceChanging = !OutputDeviceContext
            .deviceNamesEqual(deviceContext.currentDeviceName(), targetDeviceName);
        long newDevice = deviceContext.deviceHandle();
        if (deviceChanging) {
            deviceContext.closeDevice(deviceContext.deviceHandle());
            deviceContext.clear(); // the context is already torn down, so this just un-dangles the device handle
            newDevice = deviceContext.openDevice(targetDeviceName);
        }

        long newContext = newDevice == MemoryUtil.NULL ? MemoryUtil.NULL
            : deviceContext.createContext(newDevice, targetHrtfMode);
        boolean bound = newContext != MemoryUtil.NULL && deviceContext.bindContext(newContext);

        if (!bound) {
            GtnhVoice.LOG.error(
                "[Playback] Rebuild to device={} hrtf={} failed, falling back to default device/AUTO",
                targetDeviceName == null ? "<default>" : targetDeviceName,
                targetHrtfMode);
            if (newContext != MemoryUtil.NULL) ALC10.alcDestroyContext(newContext);
            if (newDevice != MemoryUtil.NULL) deviceContext.closeDevice(newDevice);

            newDevice = deviceContext.openDevice(null);
            newContext = newDevice == MemoryUtil.NULL ? MemoryUtil.NULL
                : deviceContext.createContext(newDevice, Config.HrtfMode.AUTO);
            bound = newContext != MemoryUtil.NULL && deviceContext.bindContext(newContext);

            if (!bound) {
                GtnhVoice.LOG.error("[Playback] Rebuild failed entirely; disabling output until next reconnect");
                if (newContext != MemoryUtil.NULL) ALC10.alcDestroyContext(newContext);
                if (newDevice != MemoryUtil.NULL) deviceContext.closeDevice(newDevice);
                deviceContext.clear();
                running = false; // clean shutdown on the next loop check; run()'s finally is now a no-op teardown
                return;
            }

            targetDeviceName = null;
            targetHrtfMode = Config.HrtfMode.AUTO;
        }

        // appliedHrtfMode was already recorded by createContext(), which may have degraded targetHrtfMode to AUTO.
        deviceContext.adopt(newDevice, newContext, targetDeviceName);

        // Single created-fire point for both the target and the fallback outcome - either way the new context
        // is bound and live by now, and no AL sources exist on it yet (they reappear lazily via commands).
        dispatcher.fireContextCreated(deviceContext.deviceHandle());

        GtnhVoice.LOG.info(
            "[Playback] Rebuild complete: device={} hrtfRequested={} hrtfApplied={}",
            deviceContext.currentDeviceName() == null ? "<default>" : deviceContext.currentDeviceName(),
            targetHrtfMode,
            deviceContext.appliedHrtfMode());
    }

    private void applyListenerSnapshot() {
        ListenerSnapshot snapshot = manager.currentListenerSnapshot();
        AL10.alListener3f(AL10.AL_POSITION, (float) snapshot.x(), (float) snapshot.y(), (float) snapshot.z());
        AL10.alListenerfv(
            AL10.AL_ORIENTATION,
            new float[] { snapshot.lookX(), snapshot.lookY(), snapshot.lookZ(), 0f, 1f, 0f });
    }

    /**
     * Delegates to {@link SourceChannelPool#createSourceChannel} - kept (like the three below) so
     * {@link PlaybackManager}'s command lambdas need only this thread, not its collaborators.
     */
    void createSourceChannel(UUID sourceId, BlockingQueue<short[]> frameQueue, int distance, float gain) {
        channelPool.createSourceChannel(sourceId, frameQueue, distance, gain);
    }

    /** Delegates to {@link SourceChannelPool#applyGain}. */
    void applyGain(UUID sourceId, float gain) {
        channelPool.applyGain(sourceId, gain);
    }

    /** Delegates to {@link SourceChannelPool#destroySourceChannel}. */
    void destroySourceChannel(UUID sourceId) {
        channelPool.destroySourceChannel(sourceId);
    }

    /** Delegates to {@link SourceChannelPool#resetSourceChannel}. */
    void resetSourceChannel(UUID sourceId) {
        channelPool.resetSourceChannel(sourceId);
    }
}
