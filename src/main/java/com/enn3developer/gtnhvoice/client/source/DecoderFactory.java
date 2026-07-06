package com.enn3developer.gtnhvoice.client.source;

import com.enn3developer.gtnhvoice.core.api.audio.codec.AudioDecoder;
import com.enn3developer.gtnhvoice.core.api.audio.codec.CodecException;

@FunctionalInterface
interface DecoderFactory {

    AudioDecoder create(int sampleRate, boolean stereo, int frameSize) throws CodecException;
}
