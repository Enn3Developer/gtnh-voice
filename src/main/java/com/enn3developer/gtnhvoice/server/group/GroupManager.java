package com.enn3developer.gtnhvoice.server.group;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.api.server.GtnhVoiceApi;
import com.enn3developer.gtnhvoice.api.server.group.IGroup;
import com.enn3developer.gtnhvoice.api.server.group.IGroupManager;

/**
 * The {@link IGroupManager} implementation - see the interface for the addon-facing contract (threading,
 * registry lifecycle, reserved names, priorities, routing dedup). Multi-membership: the forward index maps each
 * player to an IMMUTABLE priority-sorted snapshot of their groups, rebuilt wholesale on join/leave (memberships
 * are tiny, mutations are rare, and the UDP thread gets torn-state-proof iteration for free); every snapshot
 * ends with the shared implicit {@link LocalGroup}. The reverse index (group name to member set) answers
 * {@link #membersOf} without scanning. Both indices mutate together on the server thread; readers may see one a
 * hair ahead of the other, which at worst routes a single frame with the previous membership.
 * <p>
 * Wire ids: every group gets a stable short id for the session lifetime ({@link LocalGroup} 0,
 * {@link GlobalGroup} 1, registered groups sequentially from 2) - stamped into each routed audio packet so
 * clients can attribute what they hear; {@link #groupTableView} is the id-to-display-name table synced to
 * clients. Addons reach this only as {@link IGroupManager} via {@link GtnhVoiceApi#groupManager()}; the
 * internal lifecycle hooks ({@link #onPlayerRemoved}, {@link #clear}) stay off the interface.
 */
public final class GroupManager implements IGroupManager {

    public static final short LOCAL_GROUP_ID = 0;
    public static final short GLOBAL_GROUP_ID = 1;
    private static final short FIRST_REGISTERED_GROUP_ID = 2;

    /** Priority desc, ties by name asc - the one ordering every membership snapshot uses. */
    private static final Comparator<IGroup> ROUTING_ORDER = Comparator.comparingInt(IGroup::priority)
        .reversed()
        .thenComparing(IGroup::getName);

    private final IGroup localGroup = new LocalGroup();
    private final IGroup globalGroup = new GlobalGroup();
    /** The implicit membership of every player - what {@link #groupsOf} returns for the unassigned. */
    private final List<IGroup> localOnly;

    private final Map<UUID, List<IGroup>> groupsByPlayer = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> membersByGroupName = new ConcurrentHashMap<>();
    private final Map<String, IGroup> registeredGroups = new ConcurrentHashMap<>();
    private final Map<String, Short> groupIdsByName = new ConcurrentHashMap<>();
    private short nextGroupId = FIRST_REGISTERED_GROUP_ID; // server-thread only, like registerGroup
    private final BiConsumer<UUID, List<IGroup>> membershipListener;
    private final Runnable groupTableListener;

    /**
     * @param membershipListener invoked from {@link #join}/{@link #leave} on the caller's thread with the
     *                           player's new sorted membership snapshot (local always last), after both indices
     *                           are updated - a {@link #groupsOf} from inside the listener already sees the new
     *                           state. Must be cheap and non-blocking, since mutations run on the server thread.
     * @param groupTableListener invoked from {@link #registerGroup} after the new group has its wire id, so the
     *                           server can rebroadcast the group table to connected clients. Same cheapness
     *                           contract.
     */
    public GroupManager(@NotNull BiConsumer<UUID, List<IGroup>> membershipListener,
        @NotNull Runnable groupTableListener) {
        this.membershipListener = membershipListener;
        this.groupTableListener = groupTableListener;
        this.localOnly = Collections.singletonList(localGroup);
        groupIdsByName.put(localGroup.getName(), LOCAL_GROUP_ID);
        groupIdsByName.put(globalGroup.getName(), GLOBAL_GROUP_ID);
    }

    @Override
    public @NotNull List<IGroup> groupsOf(@NotNull UUID playerUuid) {
        return groupsByPlayer.getOrDefault(playerUuid, localOnly);
    }

    @Override
    public @Nullable IGroup byName(@NotNull String name) {
        if (name.equals(localGroup.getName())) return localGroup;
        if (name.equals(globalGroup.getName())) return globalGroup;
        return registeredGroups.get(name);
    }

    @Override
    public void registerGroup(@NotNull IGroup group) {
        String name = group.getName();
        if (name.equals(localGroup.getName()) || name.equals(globalGroup.getName())) {
            throw new IllegalArgumentException("Group name '" + name + "' is reserved for a built-in group");
        }
        if (registeredGroups.putIfAbsent(name, group) != null) {
            throw new IllegalArgumentException("A group named '" + name + "' is already registered");
        }
        groupIdsByName.put(name, nextGroupId++);
        groupTableListener.run();
    }

    @Override
    public void join(@NotNull UUID playerUuid, @NotNull IGroup group) {
        rejectLocal(group, "join");
        List<IGroup> current = groupsOf(playerUuid);
        if (current.contains(group)) return;

        List<IGroup> updated = new ArrayList<>(current.size() + 1);
        updated.addAll(current);
        updated.remove(localGroup); // re-appended last after the sort
        updated.add(group);
        publishMembership(playerUuid, updated);
        membersFor(group).add(playerUuid);
        membershipListener.accept(playerUuid, groupsOf(playerUuid));
    }

    @Override
    public void leave(@NotNull UUID playerUuid, @NotNull IGroup group) {
        rejectLocal(group, "leave");
        List<IGroup> current = groupsOf(playerUuid);
        if (!current.contains(group)) return;

        List<IGroup> updated = new ArrayList<>(current);
        updated.remove(group);
        updated.remove(localGroup);
        publishMembership(playerUuid, updated);
        membersFor(group).remove(playerUuid);
        membershipListener.accept(playerUuid, groupsOf(playerUuid));
    }

    @Override
    public @NotNull Set<UUID> membersOf(@NotNull IGroup group) {
        Set<UUID> members = membersByGroupName.get(group.getName());
        return members == null ? Collections.emptySet() : Collections.unmodifiableSet(members);
    }

    /**
     * Sorts {@code explicitGroups} into routing order, appends the implicit local tail, and swaps the player's
     * immutable snapshot in (or drops the entry entirely when no explicit memberships remain).
     */
    private void publishMembership(@NotNull UUID playerUuid, @NotNull List<IGroup> explicitGroups) {
        if (explicitGroups.isEmpty()) {
            groupsByPlayer.remove(playerUuid);
            return;
        }
        explicitGroups.sort(ROUTING_ORDER);
        explicitGroups.add(localGroup);
        groupsByPlayer.put(playerUuid, Collections.unmodifiableList(explicitGroups));
    }

    private Set<UUID> membersFor(@NotNull IGroup group) {
        return membersByGroupName.computeIfAbsent(group.getName(), name -> ConcurrentHashMap.newKeySet());
    }

    private static void rejectLocal(@NotNull IGroup group, String verb) {
        if (LocalGroup.NAME.equals(group.getName())) {
            throw new IllegalArgumentException("Cannot " + verb + " the built-in local group: it is implicit");
        }
    }

    /** The wire id stamped into routed audio for {@code group} - see the class javadoc. 0/1 are the built-ins. */
    public short groupIdOf(@NotNull IGroup group) {
        Short id = groupIdsByName.get(group.getName());
        // Unregistered foreign groups route fine but can't be attributed; local's id is the honest fallback.
        return id == null ? LOCAL_GROUP_ID : id;
    }

    /**
     * Snapshot of the wire id to display-name table for the client sync packet: built-ins first, then every
     * registered group, ordered by id.
     */
    public Map<Short, String> groupTableView() {
        Map<Short, String> table = new java.util.TreeMap<>();
        table.put(LOCAL_GROUP_ID, localGroup.getDisplayName());
        table.put(GLOBAL_GROUP_ID, globalGroup.getDisplayName());
        for (Map.Entry<String, IGroup> entry : registeredGroups.entrySet()) {
            Short id = groupIdsByName.get(entry.getKey());
            if (id != null) table.put(
                id,
                entry.getValue()
                    .getDisplayName());
        }
        return table;
    }

    /**
     * Drops {@code playerUuid}'s memberships and any per-player state their groups held. Called on logout and
     * from the stale-session reaper, alongside the server manager's own per-player cleanup. EVERY group is
     * notified - built-ins, registered, and any (possibly unregistered) currently-joined ones - not just the
     * memberships: a group may hold per-player state from before a leave moved the player out, and the hooks
     * are cheap map removals by contract.
     */
    public void onPlayerRemoved(@NotNull UUID playerUuid) {
        List<IGroup> memberships = groupsByPlayer.remove(playerUuid);
        for (Set<UUID> members : membersByGroupName.values()) {
            members.remove(playerUuid);
        }
        if (memberships != null) {
            for (IGroup group : memberships) {
                group.onPlayerRemoved(playerUuid);
            }
        }
        localGroup.onPlayerRemoved(playerUuid);
        globalGroup.onPlayerRemoved(playerUuid);
        for (IGroup group : registeredGroups.values()) {
            if (memberships != null && memberships.contains(group)) continue;
            group.onPlayerRemoved(playerUuid);
        }
    }

    /**
     * Full reset on voice server shutdown: every group - joined, registered, or built-in - gets its
     * {@link IGroup#clear} (idempotent per contract, so overlap between those sets is fine), then every index
     * is emptied and the id sequence rewinds. See {@link IGroupManager#registerGroup} for why registrations
     * must not outlive the server.
     */
    public void clear() {
        for (List<IGroup> memberships : groupsByPlayer.values()) {
            for (IGroup group : memberships) {
                group.clear();
            }
        }
        groupsByPlayer.clear();
        membersByGroupName.clear();
        for (IGroup group : registeredGroups.values()) {
            group.clear();
        }
        registeredGroups.clear();
        groupIdsByName.keySet()
            .removeIf(name -> !name.equals(localGroup.getName()) && !name.equals(globalGroup.getName()));
        nextGroupId = FIRST_REGISTERED_GROUP_ID;
        localGroup.clear();
        globalGroup.clear();
    }
}
