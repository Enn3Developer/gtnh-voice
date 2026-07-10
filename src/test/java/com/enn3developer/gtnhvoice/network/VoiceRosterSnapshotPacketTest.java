package com.enn3developer.gtnhvoice.network;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * A malformed roster snapshot claiming a huge entry count with no entries behind it must be rejected before the
 * decoder tries to allocate a map for it, guarding against an OOM/resource exhaustion from a single crafted control packet.
 */
class VoiceRosterSnapshotPacketTest {

    @Test
    void rejectsImpossiblyLargeEntryCount() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(VoiceProtocol.PROTOCOL_VERSION);
        buf.writeInt(Integer.MAX_VALUE);
        // no entries follow - the claimed count is far larger than the readable bytes could ever hold

        assertThrows(IllegalArgumentException.class, () -> new VoiceRosterSnapshotPacket().fromBytes(buf));
    }
}
