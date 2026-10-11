package com.enn3developer.gtnhvoice.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * The v6 group table wire format: every entry carries the group's internal name beside its display name, and the
 * decoder rejects structurally impossible tables (a count the remaining bytes cannot hold, a negative count, a
 * repeated id) before storing anything - the bounded-decode posture of the other control packets.
 */
class VoiceGroupTablePacketTest {

    private static VoiceGroupTablePacket roundTrip(VoiceGroupTablePacket packet) {
        ByteBuf buf = Unpooled.buffer();
        packet.toBytes(buf);
        VoiceGroupTablePacket decoded = new VoiceGroupTablePacket();
        decoded.fromBytes(buf);
        return decoded;
    }

    @Test
    void roundTripKeepsEachIdsInternalAndDisplayNameInOrder() {
        Map<Short, GroupNames> table = new LinkedHashMap<>();
        table.put((short) 0, new GroupNames("local", "local"));
        table.put((short) 1, new GroupNames("global", "global"));
        table.put((short) 2, new GroupNames("becom:squad-7", "Squad 7"));

        VoiceGroupTablePacket decoded = roundTrip(new VoiceGroupTablePacket(VoiceProtocol.PROTOCOL_VERSION, table));

        assertEquals(VoiceProtocol.PROTOCOL_VERSION, decoded.getProtocolVersion());
        assertEquals(
            List.of(
                Map.entry((short) 0, new GroupNames("local", "local")),
                Map.entry((short) 1, new GroupNames("global", "global")),
                Map.entry((short) 2, new GroupNames("becom:squad-7", "Squad 7"))),
            List.copyOf(
                decoded.getGroupTable()
                    .entrySet()));
    }

    @Test
    void rejectsACountTheRemainingBytesCannotHold() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(VoiceProtocol.PROTOCOL_VERSION);
        buf.writeShort(Short.MAX_VALUE);
        // one real entry follows - nowhere near the 4 bytes per entry the claimed count needs
        buf.writeShort(0);
        ByteBufUtils.writeUTF8String(buf, "local");
        ByteBufUtils.writeUTF8String(buf, "local");

        assertThrows(IllegalArgumentException.class, () -> new VoiceGroupTablePacket().fromBytes(buf));
    }

    @Test
    void rejectsANegativeCount() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(VoiceProtocol.PROTOCOL_VERSION);
        buf.writeShort(-1);

        assertThrows(IllegalArgumentException.class, () -> new VoiceGroupTablePacket().fromBytes(buf));
    }

    @Test
    void rejectsATableThatRepeatsAnId() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(VoiceProtocol.PROTOCOL_VERSION);
        buf.writeShort(2);
        buf.writeShort(2);
        ByteBufUtils.writeUTF8String(buf, "radio");
        ByteBufUtils.writeUTF8String(buf, "Radio");
        buf.writeShort(2);
        ByteBufUtils.writeUTF8String(buf, "phone");
        ByteBufUtils.writeUTF8String(buf, "Phone");

        assertThrows(IllegalArgumentException.class, () -> new VoiceGroupTablePacket().fromBytes(buf));
    }
}
