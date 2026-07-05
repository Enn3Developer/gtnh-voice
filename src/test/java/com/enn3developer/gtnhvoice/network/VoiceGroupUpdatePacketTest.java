package com.enn3developer.gtnhvoice.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Wire round-trip of the group display name. The name travels as UTF-8, so a non-ASCII group name
 * must survive encode/decode intact, not just the ASCII "local" default.
 */
class VoiceGroupUpdatePacketTest {

    @Test
    void roundTripsDefaultGroupName() {
        VoiceGroupUpdatePacket decoded = roundTrip(new VoiceGroupUpdatePacket(VoiceProtocol.PROTOCOL_VERSION, "local"));
        assertEquals(VoiceProtocol.PROTOCOL_VERSION, decoded.getProtocolVersion());
        assertEquals("local", decoded.getGroupDisplayName());
    }

    @Test
    void roundTripsNonAsciiGroupName() {
        String name = "Grüppe-日本語-ω";
        VoiceGroupUpdatePacket decoded = roundTrip(new VoiceGroupUpdatePacket(VoiceProtocol.PROTOCOL_VERSION, name));
        assertEquals(name, decoded.getGroupDisplayName());
    }

    private static VoiceGroupUpdatePacket roundTrip(VoiceGroupUpdatePacket packet) {
        ByteBuf buf = Unpooled.buffer();
        packet.toBytes(buf);

        VoiceGroupUpdatePacket decoded = new VoiceGroupUpdatePacket();
        decoded.fromBytes(buf);
        return decoded;
    }
}
