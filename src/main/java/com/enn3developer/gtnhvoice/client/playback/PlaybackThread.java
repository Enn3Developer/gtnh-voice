package com.enn3developer.gtnhvoice.client.playback;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

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
 */
@Lwjgl3Aware
public class PlaybackThread extends Thread {

    private static final int SAMPLE_RATE = 48_000;
    private static final int BUFFER_POOL_SIZE = 6;
    private static final long POLL_INTERVAL_MILLIS = 5L;
    private static final long LOG_INTERVAL_MILLIS = 500L;
    private static final float REFERENCE_DISTANCE = 1.0f;
    private static final float ROLLOFF_FACTOR = 1.0f;

    private final PlaybackManager manager;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final Map<UUID, SourceChannel> sourceChannels = new HashMap<>();

    private volatile boolean running = true;
    private volatile boolean openedSuccessfully = false;

    public PlaybackThread(PlaybackManager manager) {
        super("gtnhvoice-playback");
        this.manager = manager;
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
     */
    void enqueueCommand(Runnable command) {
        commands.add(command);
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
        AL10.alDistanceModel(AL10.AL_INVERSE_DISTANCE_CLAMPED);

        openedSuccessfully = true;
        GtnhVoice.LOG
            .info("[Playback] Playback started: {}Hz mono16, {} buffer pool per source", SAMPLE_RATE, BUFFER_POOL_SIZE);

        long lastLogTime = System.currentTimeMillis();

        try {
            while (running) {
                Runnable command;
                while ((command = commands.poll()) != null) {
                    command.run();
                }

                applyListenerSnapshot();

                for (Map.Entry<UUID, SourceChannel> entry : sourceChannels.entrySet()) {
                    pumpSourceChannel(entry.getKey(), entry.getValue());
                }

                long now = System.currentTimeMillis();
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
            for (Iterator<SourceChannel> it = sourceChannels.values()
                .iterator(); it.hasNext();) {
                SourceChannel channel = it.next();
                stopAndFlush(channel);
                AL10.alDeleteSources(channel.alSource);
                AL10.alDeleteBuffers(channel.bufferIds);
                it.remove();
            }
            checkAlError("teardown");

            AL.setCurrentThread(null);
            EXTThreadLocalContext.alcSetThreadContext(MemoryUtil.NULL);
            ALC10.alcDestroyContext(context);
            ALC10.alcCloseDevice(device);

            GtnhVoice.LOG.info("[Playback] Playback stopped and device closed");
        }
    }

    private void pumpSourceChannel(UUID sourceId, SourceChannel channel) {
        int processed = AL10.alGetSourcei(channel.alSource, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            channel.freeBuffers.add(AL10.alSourceUnqueueBuffers(channel.alSource));
        }
        checkAlError("alSourceUnqueueBuffers");

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

        while (!channel.freeBuffers.isEmpty()) {
            short[] frame = channel.frameQueue.poll();
            if (frame == null) break;

            int bufferId = channel.freeBuffers.poll();
            AL10.alBufferData(bufferId, AL10.AL_FORMAT_MONO16, frame, SAMPLE_RATE);
            AL10.alSourceQueueBuffers(channel.alSource, bufferId);
            checkAlError("alBufferData/alSourceQueueBuffers");
            channel.framesQueued++;
        }

        int queued = AL10.alGetSourcei(channel.alSource, AL10.AL_BUFFERS_QUEUED);
        int state = AL10.alGetSourcei(channel.alSource, AL10.AL_SOURCE_STATE);
        if (queued > 0 && state != AL10.AL_PLAYING) {
            if (state == AL10.AL_STOPPED) channel.underruns++;
            AL10.alSourcePlay(channel.alSource);
            checkAlError("alSourcePlay");
        }
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
     * for a newly seen {@code sourceId}. No-op if one already exists.
     */
    void createSourceChannel(UUID sourceId, BlockingQueue<short[]> frameQueue, int distance) {
        if (sourceChannels.containsKey(sourceId)) return;

        int source = AL10.alGenSources();
        if (!checkAlError("alGenSources")) return;

        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, REFERENCE_DISTANCE);
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, distance);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, ROLLOFF_FACTOR);

        int[] bufferIds = new int[BUFFER_POOL_SIZE];
        AL10.alGenBuffers(bufferIds);
        if (!checkAlError("alGenBuffers")) {
            AL10.alDeleteSources(source);
            return;
        }

        Deque<Integer> freeBuffers = new ArrayDeque<>(BUFFER_POOL_SIZE);
        for (int bufferId : bufferIds) freeBuffers.add(bufferId);

        sourceChannels.put(sourceId, new SourceChannel(source, bufferIds, freeBuffers, frameQueue));
        GtnhVoice.LOG.info("[Playback] AL source created for sourceId={}", sourceId);
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
    }

    private void logChannelsThrottled() {
        for (Map.Entry<UUID, SourceChannel> entry : sourceChannels.entrySet()) {
            SourceChannel channel = entry.getValue();
            int state = AL10.alGetSourcei(channel.alSource, AL10.AL_SOURCE_STATE);
            GtnhVoice.LOG.info(
                "[Playback] sourceId={} framesQueued={} underruns={} sourceState={}",
                entry.getKey(),
                channel.framesQueued,
                channel.underruns,
                alSourceStateToString(state));
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

        SourceChannel(int alSource, int[] bufferIds, Deque<Integer> freeBuffers, BlockingQueue<short[]> frameQueue) {
            this.alSource = alSource;
            this.bufferIds = bufferIds;
            this.freeBuffers = freeBuffers;
            this.frameQueue = frameQueue;
        }
    }
}
