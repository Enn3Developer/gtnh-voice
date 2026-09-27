package com.enn3developer.gtnhvoice.client.api;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.api.client.IAddonSource;
import com.enn3developer.gtnhvoice.api.client.VoiceFormat;
import com.enn3developer.gtnhvoice.client.playback.PlaybackManager;

/**
 * The one {@code IAddonSource} implementation. Holds the source's desired state (gain, mode, position) and
 * resolves the live session's {@link PlaybackManager} on every call - a null manager IS the no-session case, as in
 * {@link VoiceAddon}. {@link #write} re-issues the idempotent {@code createSource} per frame, exactly like
 * {@code VoiceSource#handleAudio}: that single call is what recreates the AL source after a reconnect or an
 * output-device/HRTF rebuild, with no session or rebuild bookkeeping here.
 * <p>
 * Threading: all state is volatile and replaced wholesale (the position array is never mutated), so setters are
 * safe from any thread, concurrently with a blocked writer.
 */
final class AddonSource implements IAddonSource {

    // How long each blocking offer waits before re-checking that the session and this source are still alive -
    // a session stop clears the manager's queue map, and a queue nobody drains would otherwise block forever.
    private static final long OFFER_SLICE_MILLIS = 50L;

    private final String addonName;
    private final UUID id;
    private final int distance;
    private final int queueCapacity;

    private volatile float gain;
    private volatile boolean positional;
    private volatile double[] position = { 0, 0, 0 };
    private volatile boolean closed;

    AddonSource(String addonName, UUID id, int distance, int queueCapacity, float gain, boolean positional) {
        this.addonName = addonName;
        this.id = id;
        this.distance = distance;
        this.queueCapacity = queueCapacity;
        this.gain = gain;
        this.positional = positional;
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public boolean write(@NotNull short[] frame) throws InterruptedException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length != VoiceFormat.FRAME_SAMPLES) throw new IllegalArgumentException(
            "frame must be " + VoiceFormat.FRAME_SAMPLES + " samples, got " + frame.length);
        if (closed) throw new IllegalStateException("addon source " + id + " of '" + addonName + "' is closed");

        PlaybackManager playback = VoiceAddon.livePlaybackManager();
        if (playback == null) return false;

        playback.createSource(id, distance, gain, queueCapacity);
        // Re-check after creating: a close() racing past the entry check has either already destroyed (and our
        // create resurrected the source - undo it here) or will destroy after this read, ordered after our create.
        if (closed) {
            playback.destroySource(id);
            return false;
        }
        playback.setPositional(id, positional);
        double[] p = position;
        playback.updateSourcePosition(id, p[0], p[1], p[2]);

        short[] copy = frame.clone();
        while (!playback.offerAddonFrame(id, copy, OFFER_SLICE_MILLIS, TimeUnit.MILLISECONDS)) {
            if (closed || !playback.hasSource(id) || VoiceAddon.livePlaybackManager() != playback) return false;
        }
        return true;
    }

    @Override
    public void flush() {
        PlaybackManager playback = VoiceAddon.livePlaybackManager();
        if (playback != null) playback.resetSource(id);
    }

    @Override
    public int bufferedFrames() {
        PlaybackManager playback = VoiceAddon.livePlaybackManager();
        return playback == null ? 0 : playback.queuedFrames(id);
    }

    @Override
    public void setPosition(double x, double y, double z) {
        position = new double[] { x, y, z };
        PlaybackManager playback = VoiceAddon.livePlaybackManager();
        if (playback != null) playback.updateSourcePosition(id, x, y, z);
    }

    @Override
    public void setPositional(boolean positional) {
        this.positional = positional;
        PlaybackManager playback = VoiceAddon.livePlaybackManager();
        if (playback != null) playback.setPositional(id, positional);
    }

    @Override
    public void setGain(float gain) {
        this.gain = AddonSourceBuilder.validateGain(gain);
        PlaybackManager playback = VoiceAddon.livePlaybackManager();
        if (playback != null) playback.setGain(id, gain);
    }

    @Override
    public boolean isLive() {
        PlaybackManager playback = VoiceAddon.livePlaybackManager();
        return playback != null && playback.hasSource(id);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        PlaybackManager playback = VoiceAddon.livePlaybackManager();
        if (playback != null) playback.destroySource(id);
        GtnhVoice.LOG.info("[AddonSource] Closed {} of '{}'", id, addonName);
    }
}
