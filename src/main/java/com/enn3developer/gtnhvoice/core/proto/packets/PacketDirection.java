/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 * Ported from Kotlin (su.plo.voice.proto.packets.PacketDirection).
 */
package com.enn3developer.gtnhvoice.core.proto.packets;

public enum PacketDirection {

    CLIENT,
    SERVER,
    ANY;

    public boolean accepts(PacketDirection direction) {
        return direction == ANY || this == ANY || direction == this;
    }
}
