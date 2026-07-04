package com.enn3developer.gtnhvoice.client;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.util.ResourceLocation;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture.Type;

/**
 * Per-UUID cache of resolved skin {@link ResourceLocation}s for the who's-talking HUD's head icons, covering
 * speakers who aren't loaded as client entities (heard but out of render range). A load is triggered at most
 * once per UUID via {@link SkinManager#func_152790_a} - its callback is handed back to the client thread by
 * SkinManager itself (it hops through {@code Minecraft.func_152344_a} before invoking the callback), so the
 * cache only needs concurrent collections, not extra locking, to be safe against the render thread reading
 * mid-update.
 *
 * <p>
 * If resolution fails outright (e.g. an offline-mode UUID with no fixer mod), {@link SkinManager} never
 * invokes the callback at all, so the UUID simply stays absent from {@link #resolved} forever and every render
 * keeps getting {@code null} back - callers are expected to draw the Steve fallback in that case.
 */
public final class HeadIconCache {

    private static final HeadIconCache INSTANCE = new HeadIconCache();

    private final ConcurrentHashMap<UUID, ResourceLocation> resolved = new ConcurrentHashMap<>();
    private final Set<UUID> requested = ConcurrentHashMap.newKeySet();

    private HeadIconCache() {}

    public static HeadIconCache getInstance() {
        return INSTANCE;
    }

    /**
     * Returns the cached skin location for {@code uuid}, or {@code null} if it hasn't (yet) resolved - the
     * first call for a given UUID triggers a one-shot load keyed off {@code name}. Callers must draw the Steve
     * fallback whenever this returns {@code null}; it may keep returning {@code null} forever if resolution
     * never succeeds.
     */
    public ResourceLocation get(UUID uuid, String name) {
        ResourceLocation cached = resolved.get(uuid);
        if (cached != null) return cached;
        if (!requested.add(uuid)) return null; // load already in flight (or failed for good)

        GtnhVoice.LOG
            .info("Head icon: triggered skin load for {} ({}), showing Steve fallback until resolved", uuid, name);

        GameProfile profile = new GameProfile(uuid, name);
        SkinManager skinManager = Minecraft.getMinecraft()
            .func_152342_ad();
        skinManager.func_152790_a(profile, (skinPart, skinLoc) -> {
            if (skinPart != Type.SKIN) return;

            resolved.put(uuid, skinLoc);
            GtnhVoice.LOG.info("Head icon: resolved and cached skin for {} ({}) -> {}", uuid, name, skinLoc);
        }, true);

        return null;
    }

    /**
     * Drops a single UUID's cached/pending state - called when a player leaves the voice roster, so the cache
     * doesn't grow unboundedly across a long session with many different speakers.
     */
    public void evict(UUID uuid) {
        resolved.remove(uuid);
        requested.remove(uuid);
    }

    /** Drops all cached/pending state - called on voice disconnect, mirroring the roster reset. */
    public void clearAll() {
        resolved.clear();
        requested.clear();
    }
}
