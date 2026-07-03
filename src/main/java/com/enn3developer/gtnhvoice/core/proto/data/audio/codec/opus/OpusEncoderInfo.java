/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 * Ported from Kotlin (su.plo.voice.proto.data.audio.codec.opus.OpusEncoderInfo).
 * About opus modes:
 * https://www.opus-codec.org/docs/html_api/group__opusencoder.html#gaa89264fd93c9da70362a0c9b96b9ca88
 * About bitrates:
 * https://www.opus-codec.org/docs/html_api/group__encoderctls.html#ga0bb51947e355b33d0cb358463b5101a7
 */
package com.enn3developer.gtnhvoice.core.proto.data.audio.codec.opus;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.enn3developer.gtnhvoice.core.proto.data.audio.codec.CodecInfo;

public class OpusEncoderInfo extends CodecInfo {

    private OpusMode mode;
    private int bitrate;

    public OpusEncoderInfo(OpusMode mode, int bitrate) {
        this.name = "opus";
        this.mode = mode;
        this.bitrate = validateBitrate(bitrate);

        Map<String, String> params = new HashMap<>();
        params.put("mode", mode.toString());
        params.put("bitrate", String.valueOf(bitrate));
        this.params = params;
    }

    /**
     * Creates OpusEncoderInfo from CodecInfo
     *
     * @throws IOException if CodecInfo is not OpusEncoderInfo
     */
    public OpusEncoderInfo(CodecInfo codecInfo) throws IOException {
        this.name = "opus";

        if (!"opus".equals(codecInfo.getName())) throw new IOException("name is not opus");

        String modeParam = codecInfo.getParams()
            .get("mode");
        if (modeParam == null) throw new IOException("mode not found in params");
        this.mode = OpusMode.valueOf(modeParam);

        String bitrateParam = codecInfo.getParams()
            .get("bitrate");
        if (bitrateParam == null) throw new IOException("bad opus bitrate");
        this.bitrate = validateStringBitrate(bitrateParam);

        this.params = codecInfo.getParams();
    }

    public OpusMode getMode() {
        return mode;
    }

    public void setMode(OpusMode mode) {
        this.mode = mode;
    }

    public int getBitrate() {
        return bitrate;
    }

    public void setBitrate(int bitrate) {
        this.bitrate = bitrate;
    }

    private int validateStringBitrate(String rawBitrate) {
        try {
            int bitrate = Integer.parseInt(rawBitrate);
            if (bitrate < 0) {
                if (bitrate != -1 && bitrate != -1000) bitrate = -1000;
            } else if (bitrate > 512000) bitrate = 512000;
            return bitrate;
        } catch (NumberFormatException ignored) {
            return -1000;
        }
    }

    private int validateBitrate(int bitrate) {
        if (bitrate < 0) {
            if (bitrate != -1 && bitrate != -1000) return -1000;
        } else if (bitrate > 512000) return 512000;

        return bitrate;
    }
}
