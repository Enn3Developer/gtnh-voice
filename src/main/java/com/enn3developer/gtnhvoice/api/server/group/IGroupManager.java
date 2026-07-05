package com.enn3developer.gtnhvoice.api.server.group;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.api.server.GtnhVoiceApi;

/**
 * Server-authoritative player-to-group assignment - the integration point for other mods: implement
 * {@link IGroup} (respecting its threading contract), {@link #registerGroup} it if {@link #byName} lookup is
 * wanted, and drive membership through {@link #assign}/{@link #groupOf}; the assignment's display name syncs to
 * the member's HUD automatically. Membership only ever changes through this API - there are no packets for it and
 * clients never request membership. Unassigned players fall through to the built-in local proximity default.
 * Obtain the live instance via {@link GtnhVoiceApi#groupManager()}.
 * <p>
 * {@link #groupOf} is read on the UDP/Netty thread (one lookup per routed audio frame); mutations run on the
 * server thread. The underlying maps are concurrent, so no further synchronization is needed.
 */
public interface IGroupManager {

    /**
     * Registers a third-party {@code group} under its {@link IGroup#getName} identity so {@link #byName}
     * resolves it. The built-in names ({@code "local"}, {@code "global"}) and already-registered names are
     * rejected with {@link IllegalArgumentException} - a name collision fails fast at addon startup instead of
     * silently shadowing a group.
     * <p>
     * Lifecycle contract: registrations do NOT survive a voice server stop - the registry is emptied, because
     * the manager persists across singleplayer world restarts and stale registrations would collide. Addons must
     * register in {@code FMLServerStartingEvent} (or later, before use) on every server start.
     */
    void registerGroup(@NotNull IGroup group);

    /**
     * Resolves a group by its {@link IGroup#getName} identity - the built-ins {@code "local"} and
     * {@code "global"} first, then anything {@link #registerGroup}ed - or {@code null} for anything else.
     * Handing the returned instance to {@link #assign} is the intended use: the local built-in resolves to the
     * same instance assign() treats as the default, so assigning it clears the assignment rather than storing a
     * redundant one.
     */
    @Nullable
    IGroup byName(@NotNull String name);

    /**
     * Assigns {@code playerUuid} to {@code group}; {@code null} returns them to the default local group.
     * Server-side callers only - membership is never client-driven.
     */
    void assign(@NotNull UUID playerUuid, @Nullable IGroup group);

    /** The group that routes {@code playerUuid}'s audio - the built-in local default unless assigned. */
    IGroup groupOf(@NotNull UUID playerUuid);
}
