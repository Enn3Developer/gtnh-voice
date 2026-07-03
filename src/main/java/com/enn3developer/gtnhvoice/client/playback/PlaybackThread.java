package com.enn3developer.gtnhvoice.client.playback;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.system.MemoryUtil;

import com.enn3developer.gtnhvoice.GtnhVoice;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Dedicated thread that owns its own ALC device+context (via {@code EXT_thread_local_context}) and streams
 * 20ms/960-sample mono 16-bit PCM frames pulled off a queue into a looping AL buffer-queue source.
 * <p>
 * Deliberately never calls {@code alcMakeContextCurrent} or {@link AL#setCurrentProcess}: those are process-global
 * and would stomp Minecraft's own Paulscode OpenAL context. Instead the context is bound thread-locally with
 * {@link EXTThreadLocalContext#alcSetThreadContext}, so this thread can drive its own OpenAL playback independently
 * of MC's audio engine.
 */
@Lwjgl3Aware
public class PlaybackThread extends Thread {

    private static final int SAMPLE_RATE = 48_000;
    private static final int BUFFER_POOL_SIZE = 6;
    private static final long POLL_INTERVAL_MILLIS = 5L;
    private static final long LOG_INTERVAL_MILLIS = 500L;

    private final BlockingQueue<short[]> frameQueue;

    private volatile boolean running = true;
    private volatile boolean openedSuccessfully = false;

    public PlaybackThread(BlockingQueue<short[]> frameQueue) {
        super("gtnhvoice-playback");
        this.frameQueue = frameQueue;
        setDaemon(true);
    }

    public boolean didOpenSuccessfully() {
        return openedSuccessfully;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        long device = ALC10.alcOpenDevice((CharSequence) null);
        if (device == MemoryUtil.NULL) {
            GtnhVoice.LOG.error("[Playback] Failed to open default playback device");
            return;
        }

        ALCCapabilities alcCaps = ALC.createCapabilities(device);
        if (!alcCaps.ALC_EXT_thread_local_context) {
            GtnhVoice.LOG.error("[Playback] ALC_EXT_thread_local_context is not supported by this OpenAL driver");
            ALC10.alcCloseDevice(device);
            return;
        }

        long context = ALC10.alcCreateContext(device, (IntBuffer) null);
        if (context == MemoryUtil.NULL || !checkAlcError(device, "alcCreateContext")) {
            ALC10.alcCloseDevice(device);
            return;
        }

        if (!EXTThreadLocalContext.alcSetThreadContext(context)) {
            GtnhVoice.LOG.error("[Playback] alcSetThreadContext failed");
            ALC10.alcDestroyContext(context);
            ALC10.alcCloseDevice(device);
            return;
        }

        ALCapabilities alCaps = AL.createCapabilities(alcCaps);
        AL.setCurrentThread(alCaps);

        int source = AL10.alGenSources();
        if (!checkAlError("alGenSources")) {
            tearDownContext(device, context);
            return;
        }

        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);

        int[] bufferIds = new int[BUFFER_POOL_SIZE];
        AL10.alGenBuffers(bufferIds);
        if (!checkAlError("alGenBuffers")) {
            AL10.alDeleteSources(source);
            tearDownContext(device, context);
            return;
        }

        Deque<Integer> freeBuffers = new ArrayDeque<>(BUFFER_POOL_SIZE);
        for (int bufferId : bufferIds) freeBuffers.add(bufferId);

        openedSuccessfully = true;
        GtnhVoice.LOG.info("[Playback] Playback started: {}Hz mono16, {} buffer pool", SAMPLE_RATE, BUFFER_POOL_SIZE);

        long framesQueued = 0;
        long underruns = 0;
        long lastLogTime = System.currentTimeMillis();

        try {
            while (running) {
                int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
                for (int i = 0; i < processed; i++) {
                    freeBuffers.add(AL10.alSourceUnqueueBuffers(source));
                }
                checkAlError("alSourceUnqueueBuffers");

                boolean starvedThisTick = false;
                while (!freeBuffers.isEmpty()) {
                    short[] frame;
                    try {
                        frame = frameQueue.poll(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        running = false;
                        break;
                    }

                    if (frame == null) {
                        starvedThisTick = true;
                        break;
                    }

                    int bufferId = freeBuffers.poll();
                    AL10.alBufferData(bufferId, AL10.AL_FORMAT_MONO16, frame, SAMPLE_RATE);
                    AL10.alSourceQueueBuffers(source, bufferId);
                    checkAlError("alBufferData/alSourceQueueBuffers");
                    framesQueued++;
                }

                int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
                int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                if (queued > 0 && state != AL10.AL_PLAYING) {
                    if (state == AL10.AL_STOPPED) underruns++;
                    AL10.alSourcePlay(source);
                    checkAlError("alSourcePlay");
                }

                long now = System.currentTimeMillis();
                if (now - lastLogTime >= LOG_INTERVAL_MILLIS) {
                    GtnhVoice.LOG.info(
                        "[Playback] framesQueued={} underruns={} sourceState={}",
                        framesQueued,
                        underruns,
                        alSourceStateToString(state));
                    lastLogTime = now;
                }

                if (!starvedThisTick && freeBuffers.isEmpty()) {
                    // Buffer pool is saturated and the queue still had data; briefly yield before rechecking.
                    try {
                        Thread.sleep(POLL_INTERVAL_MILLIS);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } finally {
            AL10.alSourceStop(source);
            checkAlError("alSourceStop");

            int remainingQueued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
            for (int i = 0; i < remainingQueued; i++) {
                AL10.alSourceUnqueueBuffers(source);
            }

            AL10.alDeleteSources(source);
            AL10.alDeleteBuffers(bufferIds);
            checkAlError("teardown");

            AL.setCurrentThread(null);
            tearDownContext(device, context);

            GtnhVoice.LOG.info("[Playback] Playback stopped and device closed, {} frames queued total", framesQueued);
        }
    }

    private void tearDownContext(long device, long context) {
        EXTThreadLocalContext.alcSetThreadContext(MemoryUtil.NULL);
        ALC10.alcDestroyContext(context);
        ALC10.alcCloseDevice(device);
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
        switch (error) {
            case AL10.AL_INVALID_NAME:
                return "AL_INVALID_NAME";
            case AL10.AL_INVALID_ENUM:
                return "AL_INVALID_ENUM";
            case AL10.AL_INVALID_VALUE:
                return "AL_INVALID_VALUE";
            case AL10.AL_INVALID_OPERATION:
                return "AL_INVALID_OPERATION";
            case AL10.AL_OUT_OF_MEMORY:
                return "AL_OUT_OF_MEMORY";
            default:
                return "unknown error code " + error;
        }
    }

    private static String alSourceStateToString(int state) {
        switch (state) {
            case AL10.AL_PLAYING:
                return "PLAYING";
            case AL10.AL_PAUSED:
                return "PAUSED";
            case AL10.AL_STOPPED:
                return "STOPPED";
            case AL10.AL_INITIAL:
                return "INITIAL";
            default:
                return "unknown state " + state;
        }
    }
}
