package com.enn3developer.gtnhvoice.server.group;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Server-authoritative player-to-group assignment. Membership only ever changes through this API - there are no
 * packets for it and clients never request membership. Unassigned players fall through to the shared
 * {@link LocalGroup} default.
 * <p>
 * This is also the integration point for other mods: implement {@link IGroup} (respecting its threading
 * contract), {@link #registerGroup} it if {@link #byName} lookup is wanted, and drive membership through
 * {@link #assign}/{@link #groupOf}; the assignment's display name syncs to the member's HUD automatically.
 * <p>
 * {@link #groupOf} is read on the UDP/Netty thread (one lookup per routed audio frame); mutations and cleanup run
 * on the server thread and the stale-session reaper thread. The assignment and registry maps are concurrent, so
 * no further synchronization is needed.
 */
public final class GroupManager {

    private final IGroup localGroup = new LocalGroup();
    private final IGroup globalGroup = new GlobalGroup();
    private final Map<UUID, IGroup> groupsByPlayer = new ConcurrentHashMap<>();
    private final Map<String, IGroup> registeredGroups = new ConcurrentHashMap<>();
    private final BiConsumer<UUID, IGroup> assignmentListener;

    /**
     * @param assignmentListener invoked from {@link #assign} on the caller's thread with the player's new
     *                           effective group (the shared {@link LocalGroup} default when assigned {@code null}),
     *                           after the
     *                           assignment map is updated - a {@link #groupOf} from inside the listener already sees
     *                           the new group.
     *                           Must be cheap and non-blocking, since assign() runs on the server thread.
     */
    public GroupManager(@NotNull BiConsumer<UUID, IGroup> assignmentListener) {
        this.assignmentListener = assignmentListener;
    }

    /** The group that routes {@code playerUuid}'s audio - the default {@link LocalGroup} unless assigned. */
    public IGroup groupOf(@NotNull UUID playerUuid) {
        return groupsByPlayer.getOrDefault(playerUuid, localGroup);
    }

    /**
     * Resolves a group by its {@link IGroup#getName} identity - the built-ins {@code "local"} and
     * {@code "global"} first, then anything {@link #registerGroup}ed - or {@code null} for anything else.
     * Handing the returned instance to {@link #assign} is the intended use: the local built-in resolves to the
     * same instance assign() treats as the default, so assigning it clears the map entry rather than storing a
     * redundant one.
     */
    public @Nullable IGroup byName(@NotNull String name) {
        if (name.equals(localGroup.getName())) return localGroup;
        if (name.equals(globalGroup.getName())) return globalGroup;
        return registeredGroups.get(name);
    }

    /**
     * Registers a third-party {@code group} under its {@link IGroup#getName} identity so {@link #byName}
     * resolves it. The built-in names and already-registered names are rejected with
     * {@link IllegalArgumentException} - a name collision fails fast at addon startup instead of silently
     * shadowing a group.
     * <p>
     * Lifecycle contract: registrations do NOT survive a voice server stop - {@link #clear} empties the
     * registry, because this manager is a singleton that persists across singleplayer world restarts and stale
     * registrations would collide. Addons must register in {@code FMLServerStartingEvent} (or later, before
     * use) on every server start.
     */
    public void registerGroup(@NotNull IGroup group) {
        String name = group.getName();
        if (name.equals(localGroup.getName()) || name.equals(globalGroup.getName())) {
            throw new IllegalArgumentException("Group name '" + name + "' is reserved for a built-in group");
        }
        if (registeredGroups.putIfAbsent(name, group) != null) {
            throw new IllegalArgumentException("A group named '" + name + "' is already registered");
        }
    }

    /**
     * Assigns {@code playerUuid} to {@code group}; {@code null} returns them to the default local group.
     * Server-side callers only - membership is never client-driven.
     */
    public void assign(@NotNull UUID playerUuid, @Nullable IGroup group) {
        IGroup effective;
        if (group == null || group == localGroup) {
            groupsByPlayer.remove(playerUuid);
            effective = localGroup;
        } else {
            groupsByPlayer.put(playerUuid, group);
            effective = group;
        }
        assignmentListener.accept(playerUuid, effective);
    }

    /**
     * Drops {@code playerUuid}'s assignment and any per-player state their groups held. Called on logout and
     * from the stale-session reaper, alongside the server manager's own per-player cleanup. EVERY group is
     * notified - built-ins, registered, and the (possibly unregistered) currently-assigned one - not just the
     * assignment: a group may hold per-player state from before a reassignment moved the player elsewhere, and
     * the hooks are cheap map removals by contract.
     */
    public void onPlayerRemoved(@NotNull UUID playerUuid) {
        IGroup assigned = groupsByPlayer.remove(playerUuid);
        if (assigned != null) assigned.onPlayerRemoved(playerUuid);
        localGroup.onPlayerRemoved(playerUuid);
        globalGroup.onPlayerRemoved(playerUuid);
        for (IGroup group : registeredGroups.values()) {
            if (group == assigned) continue;
            group.onPlayerRemoved(playerUuid);
        }
    }

    /**
     * Full reset on voice server shutdown: every group - assigned, registered, or built-in - gets its
     * {@link IGroup#clear} (idempotent per contract, so overlap between those sets is fine), then the
     * assignment map and the registry are emptied. See {@link #registerGroup} for why registrations must not
     * outlive the server.
     */
    public void clear() {
        for (IGroup group : groupsByPlayer.values()) {
            group.clear();
        }
        groupsByPlayer.clear();
        for (IGroup group : registeredGroups.values()) {
            group.clear();
        }
        registeredGroups.clear();
        localGroup.clear();
        globalGroup.clear();
    }
}
