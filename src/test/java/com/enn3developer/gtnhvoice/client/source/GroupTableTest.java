package com.enn3developer.gtnhvoice.client.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.server.group.IGroup;
import com.enn3developer.gtnhvoice.api.server.group.RoutingContext;
import com.enn3developer.gtnhvoice.network.GroupNames;
import com.enn3developer.gtnhvoice.network.VoiceGroupTablePacket;
import com.enn3developer.gtnhvoice.network.VoiceProtocol;
import com.enn3developer.gtnhvoice.server.group.GroupManager;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * {@link GroupTable}'s attribution contract: known ids resolve to the server's names, unknown ids resolve to the
 * built-in local group for playback but read as absent for callers that render "nothing yet", every packet
 * replaces the whole table, and a group's display component shows in the client's language.
 */
class GroupTableTest {

    private static final GroupNames RADIO = new GroupNames("radio", "Radio");

    @Test
    void knownIdResolvesToTheNamesTheServerSent() {
        GroupTable table = new GroupTable();
        table.replace(Map.of((short) 2, RADIO));

        assertEquals(RADIO, table.resolve((short) 2));
    }

    @Test
    void idTheTableDoesNotKnowResolvesToLocal() {
        GroupTable table = new GroupTable();
        table.replace(Map.of((short) 2, RADIO));

        assertEquals(new GroupNames("local", "local"), table.resolve((short) 3));
    }

    @Test
    void lookupOfAnIdTheTableDoesNotKnowIsNull() {
        GroupTable table = new GroupTable();
        table.replace(Map.of((short) 2, RADIO));

        assertNull(table.lookup((short) 3));
    }

    @Test
    void replaceDropsIdsTheNewTableNoLongerCarries() {
        GroupTable table = new GroupTable();
        table.replace(Map.of((short) 2, RADIO));

        table.replace(Map.of((short) 3, new GroupNames("phone", "Phone")));

        assertNull(table.lookup((short) 2), "a table packet is a full replace, not a merge");
    }

    @Test
    void clearForgetsEveryGroup() {
        GroupTable table = new GroupTable();
        table.replace(Map.of((short) 2, RADIO));

        table.clear();

        assertEquals(GroupTable.LOCAL, table.resolve((short) 2));
    }

    @Test
    void aGroupsDisplayComponentCrossesTheWireAndShowsInTheClientsLanguage() {
        String key = "gtnhvoice.test.groupTable.call";
        IGroup call = new IGroup() {

            @Override
            public String getName() {
                return "becom:call";
            }

            @Override
            public String getDisplayName() {
                return "Call";
            }

            @Override
            public IChatComponent getDisplayComponent() {
                return new ChatComponentTranslation(key);
            }

            @Override
            public void route(RoutingContext context) {}
        };
        ByteBuf wire = Unpooled.buffer();
        new VoiceGroupTablePacket(VoiceProtocol.PROTOCOL_VERSION, Map.of((short) 2, GroupManager.namesOf(call)))
            .toBytes(wire);
        VoiceGroupTablePacket received = new VoiceGroupTablePacket();
        received.fromBytes(wire);
        GroupTable table = new GroupTable();
        table.replace(received.getGroupTable());

        TestLanguage.translate(key, "Call");
        assertEquals(new GroupNames("becom:call", "Call"), table.resolve((short) 2), "an English client");
        TestLanguage.translate(key, "Chiamata");
        assertEquals(new GroupNames("becom:call", "Chiamata"), table.lookup((short) 2), "an Italian one");
    }
}
