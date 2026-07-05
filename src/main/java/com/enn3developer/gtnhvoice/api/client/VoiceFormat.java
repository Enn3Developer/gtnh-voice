package com.enn3developer.gtnhvoice.api.client;

/**
 * The fixed PCM frame format every gtnh-voice hook speaks: mono 16-bit signed PCM at {@link #SAMPLE_RATE}Hz,
 * chunked into {@link #FRAME_SAMPLES}-sample ({@link #FRAME_MILLIS}ms) frames. Constants, not configuration -
 * the values are baked into the codec pipeline and never change at runtime.
 */
public final class VoiceFormat {

    /** Samples per PCM frame handed to every filter: 20ms of mono audio at 48kHz. */
    public static final int FRAME_SAMPLES = 960;

    /** Sample rate of every PCM frame, in Hz. */
    public static final int SAMPLE_RATE = 48000;

    /** Duration of one PCM frame, in milliseconds. */
    public static final int FRAME_MILLIS = 20;

    private VoiceFormat() {}
}
