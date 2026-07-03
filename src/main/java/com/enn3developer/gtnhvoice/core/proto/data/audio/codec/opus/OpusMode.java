/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 * Ported from Kotlin (su.plo.voice.proto.data.audio.codec.opus.OpusMode).
 */
package com.enn3developer.gtnhvoice.core.proto.data.audio.codec.opus;

public enum OpusMode {

    VOIP(2048),
    AUDIO(2049),
    RESTRICTED_LOWDELAY(2051);

    private final int application;

    OpusMode(int application) {
        this.application = application;
    }

    public int getApplication() {
        return application;
    }
}
