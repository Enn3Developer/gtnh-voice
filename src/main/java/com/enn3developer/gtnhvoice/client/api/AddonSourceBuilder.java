package com.enn3developer.gtnhvoice.client.api;

import java.util.UUID;

import com.enn3developer.gtnhvoice.api.client.IAddonSource;
import com.enn3developer.gtnhvoice.api.client.IAddonSourceBuilder;
import com.enn3developer.gtnhvoice.api.client.VoiceFormat;

/**
 * The one {@code IAddonSourceBuilder} implementation. Validates every setter eagerly (fail at the call site) and
 * converts the buffer length to a whole-frame queue capacity at {@link #create()}. Not thread-safe - a builder is
 * a short-lived, single-caller object; only the created {@link AddonSource} is shared.
 */
final class AddonSourceBuilder implements IAddonSourceBuilder {

    static final float MAX_GAIN = 2.0f;
    private static final int MIN_BUFFER_MILLIS = 40;
    private static final int MAX_BUFFER_MILLIS = 1000;

    private final String addonName;
    private int distance = DEFAULT_DISTANCE;
    private float gain = 1f;
    private boolean positional = true;
    private int bufferMillis = DEFAULT_BUFFER_MILLIS;
    private boolean consumed;

    AddonSourceBuilder(String addonName) {
        this.addonName = addonName;
    }

    @Override
    public IAddonSourceBuilder distance(int blocks) {
        requireNotConsumed();
        if (blocks <= 0) throw new IllegalArgumentException("distance must be positive: " + blocks);
        distance = blocks;
        return this;
    }

    @Override
    public IAddonSourceBuilder gain(float gain) {
        requireNotConsumed();
        this.gain = validateGain(gain);
        return this;
    }

    @Override
    public IAddonSourceBuilder positional(boolean positional) {
        requireNotConsumed();
        this.positional = positional;
        return this;
    }

    @Override
    public IAddonSourceBuilder bufferMillis(int millis) {
        requireNotConsumed();
        if (millis < MIN_BUFFER_MILLIS || millis > MAX_BUFFER_MILLIS) throw new IllegalArgumentException(
            "bufferMillis must be within " + MIN_BUFFER_MILLIS + ".." + MAX_BUFFER_MILLIS + ": " + millis);
        bufferMillis = millis;
        return this;
    }

    @Override
    public IAddonSource create() {
        requireNotConsumed();
        consumed = true;

        int capacity = (bufferMillis + VoiceFormat.FRAME_MILLIS - 1) / VoiceFormat.FRAME_MILLIS;
        return new AddonSource(addonName, UUID.randomUUID(), distance, capacity, gain, positional);
    }

    /** Shared with {@link AddonSource#setGain} so builder and live updates reject the same range. */
    static float validateGain(float gain) {
        // Negated range check so NaN is rejected too.
        if (!(gain >= 0f && gain <= MAX_GAIN))
            throw new IllegalArgumentException("gain must be within 0.." + MAX_GAIN + ": " + gain);
        return gain;
    }

    private void requireNotConsumed() {
        if (consumed) throw new IllegalStateException("source builder for '" + addonName + "' already consumed");
    }
}
