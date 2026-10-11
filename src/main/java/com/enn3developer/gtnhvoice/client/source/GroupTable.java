package com.enn3developer.gtnhvoice.client.source;

import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.network.GroupNames;
import com.enn3developer.gtnhvoice.server.group.LocalGroup;

/**
 * The client's copy of the server's voice-group table - wire id to {@link GroupNames} - the one place incoming
 * audio gets attributed to a group. Replaced wholesale by every {@code VoiceGroupTablePacket} (sent at session
 * start and again whenever a group registers late) and emptied on disconnect.
 * <p>
 * Read per frame on every source's decode thread and at render rate by the HUD, written by the control-channel
 * handler: the table is an immutable snapshot published through one volatile field, so readers never lock and
 * never see a half-applied table.
 */
public final class GroupTable {

    /**
     * What an unattributable frame resolves to: the built-in local group, whose display name is its name. UDP
     * audio can outrun the reliable channel for a session's first frames, and the server stamps frames routed by a
     * group it never registered with local's wire id itself - local is the honest answer for both.
     */
    public static final GroupNames LOCAL = new GroupNames(LocalGroup.NAME, LocalGroup.NAME);

    private volatile Map<Short, GroupNames> namesById = Map.of();

    /** Swaps in {@code table} as the whole new table (full-replace, like the packet it comes from). */
    public void replace(@NotNull Map<Short, GroupNames> table) {
        namesById = Map.copyOf(table);
    }

    /** Forgets every group - the session is gone, its wire ids mean nothing any more. */
    public void clear() {
        namesById = Map.of();
    }

    /** The names behind {@code groupId}, or {@code null} while the table doesn't know it. */
    public @Nullable GroupNames lookup(short groupId) {
        return namesById.get(groupId);
    }

    /** The names behind {@code groupId}, falling back to {@link #LOCAL} for an id the table doesn't know. */
    public @NotNull GroupNames resolve(short groupId) {
        GroupNames names = namesById.get(groupId);
        return names == null ? LOCAL : names;
    }

    @Override
    public String toString() {
        return namesById.toString();
    }
}
