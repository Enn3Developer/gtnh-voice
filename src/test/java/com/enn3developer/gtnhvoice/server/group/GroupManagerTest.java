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
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.util.ChatComponentStyle;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import org.junit.jupiter.api.Test;

import com.enn3developer.gtnhvoice.api.server.group.IGroup;
import com.enn3developer.gtnhvoice.api.server.group.RoutingContext;
import com.enn3developer.gtnhvoice.network.GroupNames;

/**
 * Covers {@link GroupManager}'s multi-membership model: {@link GroupManager#byName} resolution of the built-ins,
 * {@link GroupManager#join}/{@link GroupManager#leave} snapshot maintenance (priority-desc order with the
 * implicit local tail, local join/leave rejection, idempotence), {@link GroupManager#membersOf}, wire-id
 * assignment and the {@link GroupManager#groupTableView} sync table, plus the third-party
 * {@link GroupManager#registerGroup} registry: name-collision rejection and the lifecycle hooks
 * ({@link GroupManager#clear}, {@link GroupManager#onPlayerRemoved}) reaching registered groups.
 */
class GroupManagerTest {

    /** Minimal registrable group that records which lifecycle hooks GroupManager forwarded to it. */
    private static final class RecordingGroup implements IGroup {

        private final String name;
        private final int priority;
        private final List<UUID> removedPlayers = new ArrayList<>();
        private boolean cleared;

        private RecordingGroup(String name) {
            this(name, 0);
        }

        private RecordingGroup(String name, int priority) {
            this.name = name;
            this.priority = priority;
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
        public int priority() {
            return priority;
        }

        @Override
        public void route(RoutingContext context) {}

        @Override
        public void onPlayerRemoved(UUID playerUuid) {
            removedPlayers.add(playerUuid);
        }

        @Override
        public void clear() {
            cleared = true;
        }
    }

    private final List<List<IGroup>> membershipChanges = new ArrayList<>();
    private int tableChanges;
    private final GroupManager manager = new GroupManager(
        (playerUuid, groups) -> membershipChanges.add(groups),
        () -> tableChanges++);

    @Test
    void byNameResolvesOnlyTheBuiltIns() {
        assertInstanceOf(LocalGroup.class, manager.byName("local"));
        assertInstanceOf(GlobalGroup.class, manager.byName("global"));
        assertEquals(Arrays.asList(manager.byName("local")), manager.groupsOf(UUID.randomUUID()));
        assertNull(manager.byName("proximity"));
        assertNull(manager.byName(""));
    }

    @Test
    void joinAndLeaveMaintainThePrioritySortedSnapshotWithLocalLast() {
        UUID playerUuid = UUID.randomUUID();
        IGroup local = manager.byName("local");
        IGroup global = manager.byName("global"); // Integer.MAX_VALUE - overrides every registered group
        RecordingGroup party = new RecordingGroup("party", 0);
        RecordingGroup command = new RecordingGroup("command", 20);
        manager.registerGroup(party);
        manager.registerGroup(command);

        manager.join(playerUuid, party);
        manager.join(playerUuid, global);
        manager.join(playerUuid, command);

        assertEquals(Arrays.asList(global, command, party, local), manager.groupsOf(playerUuid));

        manager.leave(playerUuid, global);
        assertEquals(Arrays.asList(command, party, local), manager.groupsOf(playerUuid));

        manager.leave(playerUuid, command);
        manager.leave(playerUuid, party);
        assertEquals(Arrays.asList(local), manager.groupsOf(playerUuid));
        assertEquals(manager.groupsOf(UUID.randomUUID()), manager.groupsOf(playerUuid));
    }

    @Test
    void joinIsIdempotentAndTracksMembers() {
        UUID playerUuid = UUID.randomUUID();
        IGroup global = manager.byName("global");

        manager.join(playerUuid, global);
        manager.join(playerUuid, global);

        assertEquals(2, manager.groupsOf(playerUuid).size()); // global + implicit local
        assertEquals(1, membershipChanges.size(), "the idempotent second join must not re-fire the listener");
        assertTrue(
            manager.membersOf(global)
                .contains(playerUuid));

        manager.leave(playerUuid, global);
        assertTrue(
            manager.membersOf(global)
                .isEmpty());
    }

    @Test
    void localIsNotJoinableOrLeavable() {
        UUID playerUuid = UUID.randomUUID();
        IGroup local = manager.byName("local");

        assertThrows(IllegalArgumentException.class, () -> manager.join(playerUuid, local));
        assertThrows(IllegalArgumentException.class, () -> manager.leave(playerUuid, local));
    }

    @Test
    void wireIdsAreStableAndTheTableCarriesDisplayNames() {
        RecordingGroup party = new RecordingGroup("party");
        manager.registerGroup(party);

        assertEquals(GroupManager.LOCAL_GROUP_ID, manager.groupIdOf(manager.byName("local")));
        assertEquals(GroupManager.GLOBAL_GROUP_ID, manager.groupIdOf(manager.byName("global")));
        assertEquals((short) 2, manager.groupIdOf(party));
        assertEquals(1, tableChanges, "registerGroup must announce the table change");

        assertEquals(new GroupNames("party", "party"), manager.groupTableView().get((short) 2));
        assertEquals(new GroupNames("local", "local"), manager.groupTableView().get(GroupManager.LOCAL_GROUP_ID));
    }

    @Test
    void groupTableCarriesEachGroupsInternalNameBesideItsDisplayName() {
        IGroup squad = new IGroup() {

            @Override
            public String getName() {
                return "becom:squad-7";
            }

            @Override
            public String getDisplayName() {
                return "Squad 7";
            }

            @Override
            public void route(RoutingContext context) {}
        };
        manager.registerGroup(squad);

        Map<Short, GroupNames> table = manager.groupTableView();

        assertEquals(new GroupNames("becom:squad-7", "Squad 7"), table.get((short) 2));
        assertEquals(new GroupNames("global", "global"), table.get((short) 1));
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
    void playerJoinedToARegisteredGroupGetsItsOnPlayerRemoved() {
        RecordingGroup party = new RecordingGroup("party");
        manager.registerGroup(party);
        UUID playerUuid = UUID.randomUUID();
        manager.join(playerUuid, party);

        manager.onPlayerRemoved(playerUuid);

        assertEquals(Arrays.asList(playerUuid), party.removedPlayers);
        assertEquals(Arrays.asList(manager.byName("local")), manager.groupsOf(playerUuid));
        assertTrue(
            manager.membersOf(party)
                .isEmpty());
    }

    @Test
    void registeredGroupGetsOnPlayerRemovedEvenAfterLeaving() {
        RecordingGroup party = new RecordingGroup("party");
        manager.registerGroup(party);
        UUID playerUuid = UUID.randomUUID();
        manager.join(playerUuid, party);
        manager.leave(playerUuid, party);

        manager.onPlayerRemoved(playerUuid);

        assertEquals(Arrays.asList(playerUuid), party.removedPlayers);
    }

    @Test
    void groupTableCarriesTheDisplayComponentAsChatJson() {
        manager.registerGroup(
            displayComponentGroup("becom:call", () -> new ChatComponentTranslation("becom.group.call", "7")));

        Map<Short, GroupNames> table = manager.groupTableView();

        assertEquals(
            new GroupNames("becom:call", "becom:call", "{\"translate\":\"becom.group.call\",\"with\":[\"7\"]}"),
            table.get((short) 2));
        assertNull(
            table.get(GroupManager.LOCAL_GROUP_ID)
                .displayComponentJson(),
            "a group without a display component sends none");
    }

    @Test
    void aDisplayComponentThatCannotBeSentLeavesTheTableToTheDisplayName() {
        manager.registerGroup(displayComponentGroup("throws", () -> { throw new IllegalStateException("addon bug"); }));
        manager.registerGroup(displayComponentGroup("unserializable", UnserializableComponent::new));
        manager.registerGroup(displayComponentGroup("tooLong", () -> new ChatComponentText("x".repeat(20_000))));

        Map<Short, GroupNames> table = manager.groupTableView();

        assertEquals(new GroupNames("throws", "throws"), table.get((short) 2));
        assertEquals(new GroupNames("unserializable", "unserializable"), table.get((short) 3));
        assertEquals(new GroupNames("tooLong", "tooLong"), table.get((short) 4));
    }

    /** A group named {@code name}, displayed as its name, whose display component comes from {@code component}. */
    private static IGroup displayComponentGroup(String name, Supplier<IChatComponent> component) {
        return new IGroup() {

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDisplayName() {
                return name;
            }

            @Override
            public IChatComponent getDisplayComponent() {
                return component.get();
            }

            @Override
            public void route(RoutingContext context) {}
        };
    }

    /** A kind of component IChatComponent.Serializer has no JSON for. */
    private static final class UnserializableComponent extends ChatComponentStyle {

        @Override
        public String getUnformattedTextForChat() {
            return "?";
        }

        @Override
        public IChatComponent createCopy() {
            return new UnserializableComponent();
        }
    }
}
