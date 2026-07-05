package com.enn3developer.gtnhvoice.server.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

/**
 * Covers {@link GroupManager#byName} resolution of the two built-ins and the assign-global -> assign-local round
 * trip restoring the default (assigning the local built-in must take the same map-entry-clearing path as
 * {@code null}, so byName's returned instance and assign's identity check have to agree), plus the third-party
 * {@link GroupManager#registerGroup} registry: name-collision rejection and the lifecycle hooks
 * ({@link GroupManager#clear}, {@link GroupManager#onPlayerRemoved}) reaching registered groups.
 */
class GroupManagerTest {

    /** Minimal registrable group that records which lifecycle hooks GroupManager forwarded to it. */
    private static final class RecordingGroup implements IGroup {

        private final String name;
        private final List<UUID> removedPlayers = new ArrayList<>();
        private boolean cleared;

        private RecordingGroup(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDisplayName() {
            return name;
        }

        @Override
        public void route(VoiceServerSession speakerSession, PlayerAudioPacket audio, RoutingContext context) {}

        @Override
        public void onPlayerRemoved(UUID playerUuid) {
            removedPlayers.add(playerUuid);
        }

        @Override
        public void clear() {
            cleared = true;
        }
    }

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

    @Test
    void registerGroupMakesByNameResolveIt() {
        RecordingGroup party = new RecordingGroup("party");

        manager.registerGroup(party);

        assertSame(party, manager.byName("party"));
    }

    @Test
    void registeringACollidingNameThrows() {
        manager.registerGroup(new RecordingGroup("party"));

        assertThrows(IllegalArgumentException.class, () -> manager.registerGroup(new RecordingGroup("party")));
        assertThrows(IllegalArgumentException.class, () -> manager.registerGroup(new RecordingGroup("local")));
        assertThrows(IllegalArgumentException.class, () -> manager.registerGroup(new RecordingGroup("global")));
    }

    @Test
    void clearEmptiesTheRegistryAndClearsRegisteredGroups() {
        RecordingGroup party = new RecordingGroup("party");
        manager.registerGroup(party);

        manager.clear();

        assertNull(manager.byName("party"));
        assertTrue(party.cleared);
    }

    @Test
    void playerAssignedToARegisteredGroupGetsItsOnPlayerRemoved() {
        RecordingGroup party = new RecordingGroup("party");
        manager.registerGroup(party);
        UUID playerUuid = UUID.randomUUID();
        manager.assign(playerUuid, party);

        manager.onPlayerRemoved(playerUuid);

        assertEquals(Arrays.asList(playerUuid), party.removedPlayers);
        assertSame(manager.byName("local"), manager.groupOf(playerUuid));
    }

    @Test
    void registeredGroupGetsOnPlayerRemovedEvenAfterReassignment() {
        RecordingGroup party = new RecordingGroup("party");
        manager.registerGroup(party);
        UUID playerUuid = UUID.randomUUID();
        manager.assign(playerUuid, party);
        manager.assign(playerUuid, null);

        manager.onPlayerRemoved(playerUuid);

        assertEquals(Arrays.asList(playerUuid), party.removedPlayers);
    }
}
