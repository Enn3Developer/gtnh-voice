package com.enn3developer.gtnhvoice.client.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.network.GroupNames;

/**
 * {@link GroupTable}'s attribution contract: known ids resolve to the server's names, unknown ids resolve to the
 * built-in local group for playback but read as absent for callers that render "nothing yet", and every packet
 * replaces the whole table.
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
}
