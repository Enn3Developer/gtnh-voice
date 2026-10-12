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
 * The v7 group table wire format: every entry carries the group's internal name, its display name and its display
 * component (IChatComponent JSON, an empty string for none), and the decoder rejects structurally impossible tables
 * (a count the remaining bytes cannot hold, a negative count, a repeated id) before storing anything - the
 * bounded-decode posture of the other control packets.
 */
class VoiceGroupTablePacketTest {

    private static final String CALL_JSON = "{\"translate\":\"becom.group.call\"}";

    private static VoiceGroupTablePacket roundTrip(VoiceGroupTablePacket packet) {
        ByteBuf buf = Unpooled.buffer();
        packet.toBytes(buf);
        VoiceGroupTablePacket decoded = new VoiceGroupTablePacket();
        decoded.fromBytes(buf);
        return decoded;
    }

    @Test
    void roundTripKeepsEachIdsNamesAndDisplayComponentInOrder() {
        Map<Short, GroupNames> table = new LinkedHashMap<>();
        table.put((short) 0, new GroupNames("local", "local"));
        table.put((short) 1, new GroupNames("global", "global"));
        table.put((short) 2, new GroupNames("becom:squad-7", "Squad 7"));
        table.put((short) 3, new GroupNames("becom:call", "Call", CALL_JSON));

        VoiceGroupTablePacket decoded = roundTrip(new VoiceGroupTablePacket(VoiceProtocol.PROTOCOL_VERSION, table));

        assertEquals(VoiceProtocol.PROTOCOL_VERSION, decoded.getProtocolVersion());
        assertEquals(
            List.of(
                Map.entry((short) 0, new GroupNames("local", "local")),
                Map.entry((short) 1, new GroupNames("global", "global")),
                Map.entry((short) 2, new GroupNames("becom:squad-7", "Squad 7")),
                Map.entry((short) 3, new GroupNames("becom:call", "Call", CALL_JSON))),
            List.copyOf(
                decoded.getGroupTable()
                    .entrySet()));
    }

    @Test
    void anEmptyDisplayComponentOnTheWireReadsAsNone() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(VoiceProtocol.PROTOCOL_VERSION);
        buf.writeShort(1);
        writeEntry(buf, 2, "radio", "Radio", "");

        VoiceGroupTablePacket decoded = new VoiceGroupTablePacket();
        decoded.fromBytes(buf);

        assertEquals(Map.of((short) 2, new GroupNames("radio", "Radio", null)), decoded.getGroupTable());
    }

    @Test
    void rejectsACountTheRemainingBytesCannotHold() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(VoiceProtocol.PROTOCOL_VERSION);
        buf.writeShort(Short.MAX_VALUE);
        // one real entry follows - nowhere near the 5 bytes per entry the claimed count needs
        writeEntry(buf, 0, "local", "local", "");

        assertThrows(IllegalArgumentException.class, () -> new VoiceGroupTablePacket().fromBytes(buf));
    }

    @Test
    void rejectsAnEntryTooShortToHoldADisplayComponent() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(VoiceProtocol.PROTOCOL_VERSION);
        buf.writeShort(1);
        // a v6 entry: the id and two empty strings, 4 bytes - one short of the v7 minimum
        buf.writeShort(0);
        ByteBufUtils.writeUTF8String(buf, "");
        ByteBufUtils.writeUTF8String(buf, "");

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
        writeEntry(buf, 2, "radio", "Radio", "");
        writeEntry(buf, 2, "phone", "Phone", "");

        assertThrows(IllegalArgumentException.class, () -> new VoiceGroupTablePacket().fromBytes(buf));
    }

    private static void writeEntry(ByteBuf buf, int id, String name, String displayName, String displayComponent) {
        buf.writeShort(id);
        ByteBufUtils.writeUTF8String(buf, name);
        ByteBufUtils.writeUTF8String(buf, displayName);
        ByteBufUtils.writeUTF8String(buf, displayComponent);
    }
}
