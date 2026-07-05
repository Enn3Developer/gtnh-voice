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
 * {@link #groupOf} is read on the UDP/Netty thread (one lookup per routed audio frame); mutations and cleanup run
 * on the server thread and the stale-session reaper thread. The assignment map is concurrent, so no further
 * synchronization is needed.
 */
public final class GroupManager {

    private final IGroup localGroup = new LocalGroup();
    private final Map<UUID, IGroup> groupsByPlayer = new ConcurrentHashMap<>();
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
     * from the stale-session reaper, alongside the server manager's own per-player cleanup. The default group is
     * always notified too - it may hold state for players that were never explicitly assigned anywhere.
     */
    public void onPlayerRemoved(@NotNull UUID playerUuid) {
        IGroup assigned = groupsByPlayer.remove(playerUuid);
        if (assigned != null) assigned.onPlayerRemoved(playerUuid);
        localGroup.onPlayerRemoved(playerUuid);
    }

    /** Full reset on voice server shutdown. */
    public void clear() {
        for (IGroup group : groupsByPlayer.values()) {
            group.clear();
        }
        groupsByPlayer.clear();
        localGroup.clear();
    }
}
