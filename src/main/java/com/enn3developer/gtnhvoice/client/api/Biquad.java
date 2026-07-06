package com.enn3developer.gtnhvoice.client.api;

import com.enn3developer.gtnhvoice.api.client.VoiceFormat;

/**
 * One RBJ-cookbook 2nd-order IIR section (high-pass or low-pass), the DSP behind {@link IPcmChain#highPass} /
 * {@link IPcmChain#lowPass} (and, cascaded, {@link IPcmChain#bandPass}). Coefficients are derived once at
 * construction from the cutoff and Q; {@link #step(double)} runs a Direct-Form-I difference equation whose four
 * state samples ({@code x1,x2,y1,y2}) are the filter's delay line. Stateful and single-threaded by construction:
 * exactly one instance per pipeline, stepped sequentially, so the state fields need no locking.
 */
final class Biquad {

    /** Butterworth (maximally flat) Q, the default when the addon gives no resonance. */
    static final double BUTTERWORTH_Q = 1.0 / Math.sqrt(2.0);

    private final double b0;
    private final double b1;
    private final double b2;
    private final double a1;
    private final double a2;

    private double x1;
    private double x2;
    private double y1;
    private double y2;

    private Biquad(double b0, double b1, double b2, double a1, double a2) {
        this.b0 = b0;
        this.b1 = b1;
        this.b2 = b2;
        this.a1 = a1;
        this.a2 = a2;
    }

    static Biquad highPass(double hz, double q) {
        double w0 = omega(hz);
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);
        double b0 = (1.0 + cos) / 2.0;
        double b1 = -(1.0 + cos);
        double b2 = (1.0 + cos) / 2.0;
        return normalized(b0, b1, b2, 1.0 + alpha, -2.0 * cos, 1.0 - alpha);
    }

    static Biquad lowPass(double hz, double q) {
        double w0 = omega(hz);
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);
        double b0 = (1.0 - cos) / 2.0;
        double b1 = 1.0 - cos;
        double b2 = (1.0 - cos) / 2.0;
        return normalized(b0, b1, b2, 1.0 + alpha, -2.0 * cos, 1.0 - alpha);
    }

    private static double omega(double hz) {
        return 2.0 * Math.PI * hz / VoiceFormat.SAMPLE_RATE;
    }

    private static Biquad normalized(double b0, double b1, double b2, double a0, double a1, double a2) {
        return new Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
    }

    double step(double x) {
        double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = x;
        y2 = y1;
        y1 = y;
        return y;
    }
}
