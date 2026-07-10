package com.enn3developer.gtnhvoice.core.audio;

/**
 * An immutable audio duration, counted in whole 20ms Opus frames. Exists to keep frame-domain arithmetic typed:
 * durations combine with {@link #add}/{@link #sub}, and crossing into the clock domain is a terminal operation
 * ({@link #asTimestamp}) that collapses to a raw {@code long} timestamp, so a timestamp can't accidentally be
 * fed back into duration math.
 * <p>
 * Frame counts can be remote-influenced wire values (a sequence-number offset is dimensionally a frame count,
 * and an untrusted server chooses every sequence number), so every operation saturates instead of wrapping:
 * a pathological count (e.g. {@code Long.MAX_VALUE}) clamps to the far future rather than overflowing into the
 * past and reading as already due. Overflow only occurs around 4.6e17 frames - astronomically beyond any real
 * stream - so legitimate values compute exactly.
 */
public final class AudioUnit {

    public static final long FRAME_DURATION_MILLIS = 20L;

    private final long frames;

    private AudioUnit(long frames) {
        this.frames = frames;
    }

    public static AudioUnit frames(long frames) {
        return new AudioUnit(frames);
    }

    public long asFrames() {
        return frames;
    }

    /** This duration in milliseconds, saturating at {@code Long.MAX_VALUE}/{@code Long.MIN_VALUE}. */
    public long asMillis() {
        long frames = asFrames();
        if (frames > Long.MAX_VALUE / FRAME_DURATION_MILLIS) return Long.MAX_VALUE;
        if (frames < Long.MIN_VALUE / FRAME_DURATION_MILLIS) return Long.MIN_VALUE;
        return frames * FRAME_DURATION_MILLIS;
    }

    public AudioUnit add(AudioUnit other) {
        return new AudioUnit(saturatingAdd(asFrames(), other.asFrames()));
    }

    public AudioUnit sub(AudioUnit other) {
        return new AudioUnit(saturatingSubtract(asFrames(), other.asFrames()));
    }

    /**
     * Applies this duration to a point on a clock, leaving the frame domain: returns
     * {@code anchor + asMillis()} with saturation. Terminal by design - the result is a timestamp, and no
     * duration operation accepts one.
     */
    public long asTimestamp(long anchor) {
        return saturatingAdd(anchor, asMillis());
    }

    private static long saturatingAdd(long base, long delta) {
        long sum = base + delta;
        boolean overflowed = ((base ^ sum) & (delta ^ sum)) < 0;
        if (!overflowed) return sum;
        return delta >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
    }

    private static long saturatingSubtract(long minuend, long subtrahend) {
        long difference = minuend - subtrahend;
        boolean overflowed = ((minuend ^ subtrahend) & (minuend ^ difference)) < 0;
        if (!overflowed) return difference;
        return minuend >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof AudioUnit)) return false;
        return frames == ((AudioUnit) obj).frames;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(frames);
    }

    @Override
    public String toString() {
        return asFrames() + " frames";
    }
}
