package com.enn3developer.gtnhvoice.client.api;

/**
 * The {@code short} PCM <-> normalized {@code double} conversion at an {@link IPcmChain} pipeline's boundaries.
 * Stages run in {@code [-1, 1]}; this maps the mod's 16-bit frames into that domain on the way in and clamps
 * back to the signed-16-bit range on the way out, so any stage overshoot hard-clips at full scale rather than
 * wrapping. Full-scale is {@code 32768} (the negative extreme's magnitude), which keeps a decoded
 * {@code -32768} at exactly {@code -1.0} and caps the positive side at {@code +32767}.
 */
final class PcmSamples {

    private static final double FULL_SCALE = 32768.0;
    private static final int MAX_PCM = 32767;
    private static final int MIN_PCM = -32768;

    private PcmSamples() {}

    /** Maps a 16-bit PCM sample into {@code [-1, 1]}. */
    static double toNormalized(short pcm) {
        return pcm / FULL_SCALE;
    }

    /** Maps a normalized sample back to 16-bit PCM, clamping overshoot to the signed-16-bit range. */
    static short toPcm(double normalized) {
        long scaled = Math.round(normalized * FULL_SCALE);
        if (scaled > MAX_PCM) return MAX_PCM;
        if (scaled < MIN_PCM) return MIN_PCM;
        return (short) scaled;
    }
}
