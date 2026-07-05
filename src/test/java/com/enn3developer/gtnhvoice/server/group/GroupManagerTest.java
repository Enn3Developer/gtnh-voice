package com.enn3developer.gtnhvoice.server.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link GroupManager#byName} resolution of the two built-ins and the assign-global -> assign-local round
 * trip restoring the default (assigning the local built-in must take the same map-entry-clearing path as
 * {@code null}, so byName's returned instance and assign's identity check have to agree).
 */
class GroupManagerTest {

    private final List<IGroup> assignments = new ArrayList<>();
    private final GroupManager manager = new GroupManager((playerUuid, group) -> assignments.add(group));

    @Test
    void byNameResolvesOnlyTheBuiltIns() {
        assertInstanceOf(LocalGroup.class, manager.byName("local"));
        assertInstanceOf(GlobalGroup.class, manager.byName("global"));
        assertSame(manager.byName("local"), manager.groupOf(UUID.randomUUID()));
        assertNull(manager.byName("proximity"));
        assertNull(manager.byName(""));
    }

    @Test
    void assignGlobalThenLocalRestoresTheDefault() {
        UUID playerUuid = UUID.randomUUID();
        IGroup global = manager.byName("global");
        IGroup local = manager.byName("local");

        manager.assign(playerUuid, global);
        assertSame(global, manager.groupOf(playerUuid));

        manager.assign(playerUuid, local);
        assertSame(local, manager.groupOf(playerUuid));
        assertSame(manager.groupOf(UUID.randomUUID()), manager.groupOf(playerUuid));

        assertEquals(Arrays.asList(global, local), assignments);
    }
}
