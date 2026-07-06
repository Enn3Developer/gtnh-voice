/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.api.util;

/**
 * Utility class for audio-related operations.
 */
public final class AudioUtil {

    /**
     * Converts an array of floats to an array of shorts.
     *
     * @param floats The float array to convert.
     * @return An array of shorts.
     */
    public static short[] floatsToShorts(float[] floats) {
        short[] shorts = new short[floats.length];

        for (int i = 0; i < floats.length; i++) {
            // Clamp to prevent overdrive causing clipping
            // (https://github.com/remjey/mumble/commit/f16b47c81aceaf0c8704b355d9316bf685cb3704)
            float clamped = Math.max((float) Short.MIN_VALUE, Math.min((float) Short.MAX_VALUE, floats[i]));
            shorts[i] = (short) clamped;
        }

        return shorts;
    }

    /**
     * Converts an array of shorts to an array of floats.
     *
     * @param input The short array to convert.
     * @return An array of floats.
     */
    public static float[] shortsToFloats(short[] input) {
        float[] ret = new float[input.length];

        for (int i = 0; i < input.length; i++) {
            ret[i] = (float) input[i];
        }

        return ret;
    }

    /**
     * Calculates the audio level of a range of shorts.
     *
     * @param samples The audio samples.
     * @param offset  The offset from the start of the samples array.
     * @param length  The number of samples to process for the calculation.
     * @return The calculated audio level.
     */
    public static double calculateAudioLevel(short[] samples, int offset, int length) {
        double rms = 0D; // root mean square (RMS) amplitude

        for (int i = offset; i < length; i++) {
            double sample = (double) samples[i] / Short.MAX_VALUE;
            rms += sample * sample;
        }

        return calculateAudioLevelFromRMS(rms, samples.length);
    }

    /**
     * Converts RMS and sample count to an audio level.
     *
     * @param rms         Root Mean Square (RMS) value.
     * @param sampleCount The count of samples.
     * @return The audio level.
     */
    public static double calculateAudioLevelFromRMS(double rms, int sampleCount) {
        rms = (sampleCount == 0) ? 0 : Math.sqrt(rms / sampleCount);

        double db;

        if (rms > 0D) {
            db = Math.min(Math.max(20D * Math.log10(rms), -127D), 0D);
        } else {
            db = -127D;
        }

        return db;
    }

    /**
     * Gets the highest absolute sample value in an array of shorts.
     *
     * @param samples The audio samples.
     * @return The highest absolute sample value.
     */
    public static short getHighestAbsoluteSample(short[] samples) {
        short max = 0;
        for (short sample : samples) {
            if (sample == Short.MIN_VALUE) {
                sample += 1;
            }

            short abs = (short) Math.abs(sample);
            if (abs > max) {
                max = abs;
            }
        }

        return max;
    }

    private AudioUtil() {}
}
