/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 * Ported from Kotlin (su.plo.voice.proto.data.audio.codec.opus.OpusDecoderInfo).
 */
package com.enn3developer.gtnhvoice.core.proto.data.audio.codec.opus;

import java.io.IOException;

import com.enn3developer.gtnhvoice.core.proto.data.audio.codec.CodecInfo;
import com.google.common.io.ByteArrayDataOutput;

public class OpusDecoderInfo extends CodecInfo {

    public OpusDecoderInfo() {
        this.name = "opus";
    }

    public OpusDecoderInfo(CodecInfo codecInfo) throws IOException {
        this();
        if (!"opus".equals(codecInfo.getName())) throw new IOException("name is not opus");
    }

    @Override
    public void serialize(ByteArrayDataOutput out) {
        out.writeUTF("opus");

        out.writeInt(0);
    }
}
