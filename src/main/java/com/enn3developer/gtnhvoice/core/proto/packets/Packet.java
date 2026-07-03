/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets;

import java.io.IOException;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;

public interface Packet<T extends PacketHandler> {

    void read(ByteArrayDataInput in) throws IOException;

    void write(ByteArrayDataOutput out) throws IOException;

    void handle(T handler);
}
