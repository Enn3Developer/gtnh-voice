package com.enn3developer.gtnhvoice.api.server.group;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.api.server.GtnhVoiceApi;

/**
 * Server-authoritative multi-group membership - the integration point for other mods: implement {@link IGroup}
 * (respecting its threading contract), {@link #registerGroup} it, and drive membership through
 * {@link #join}/{@link #leave}. A player may be in any number of groups at once; everyone is implicitly in the
 * built-in local proximity group, which is not joinable or leavable and always routes last
 * ({@link IGroup#priority()} {@link Integer#MIN_VALUE}). Membership only ever changes through this API - there
 * are no packets for it and clients never request membership. Obtain the live instance via
 * {@link GtnhVoiceApi#groupManager()}.
 * <p>
 * Routing dedup contract: a speaker's groups route each frame in priority order (higher first, local last);
 * a recipient selected by more than one group receives ONLY the highest-priority group's packet - one packet
 * per recipient per frame, stamped with the winning group's source state and identity.
 * <p>
 * {@link #groupsOf} is read on the UDP/Netty thread (one lookup per routed audio frame) and returns an
 * immutable pre-sorted snapshot; mutations run on the server thread. The underlying maps are concurrent, so no
 * further synchronization is needed.
 */
public interface IGroupManager {

    /**
     * Registers a third-party {@code group} under its {@link IGroup#getName} identity so {@link #byName}
     * resolves it, and assigns it the wire id clients use to attribute incoming audio. The built-in names
     * ({@code "local"}, {@code "global"}) and already-registered names are rejected with
     * {@link IllegalArgumentException} - a name collision fails fast at addon startup instead of silently
     * shadowing a group.
     * <p>
     * Lifecycle contract: registrations do NOT survive a voice server stop - the registry is emptied, because
     * the manager persists across singleplayer world restarts and stale registrations would collide. Addons must
     * register in {@code FMLServerStartingEvent} (or later, before use) on every server start.
     */
    void registerGroup(@NotNull IGroup group);

    /**
     * Resolves a group by its {@link IGroup#getName} identity - the built-ins {@code "local"} and
     * {@code "global"} first, then anything {@link #registerGroup}ed - or {@code null} for anything else.
     */
    @Nullable
    IGroup byName(@NotNull String name);

    /**
     * Adds {@code playerUuid} to {@code group}. Rejects the built-in local group with
     * {@link IllegalArgumentException} (everyone is implicitly in it); joining a group the player is already in
     * is a no-op. Server-side callers only - membership is never client-driven.
     */
    void join(@NotNull UUID playerUuid, @NotNull IGroup group);

    /**
     * Removes {@code playerUuid} from {@code group}. Rejects the built-in local group with
     * {@link IllegalArgumentException}; leaving a group the player is not in is a no-op.
     */
    void leave(@NotNull UUID playerUuid, @NotNull IGroup group);

    /**
     * Every group routing {@code playerUuid}'s audio, sorted for routing: priority descending
     * ({@link IGroup#priority()}, ties by name), always ending with the implicit built-in local group.
     * Immutable snapshot - safe to iterate on the UDP/Netty thread with no locking.
     */
    @NotNull
    List<IGroup> groupsOf(@NotNull UUID playerUuid);

    /**
     * The players currently in {@code group}, as an unmodifiable live view (weakly consistent iteration, like
     * the concurrent set backing it). Empty for the local built-in - its membership is "everyone", tracked
     * nowhere.
     */
    @NotNull
    Set<UUID> membersOf(@NotNull IGroup group);
}
