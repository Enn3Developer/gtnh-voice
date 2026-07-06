package com.enn3developer.gtnhvoice.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.Gui;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Shared face+hat head-icon resolution/drawing for the who's-talking HUD and the Players GUI: prefers a loaded
 * {@link AbstractClientPlayer}'s own (already-resolved) skin, falling back to {@link HeadIconCache} (which lazily
 * triggers a load and returns {@code null} - drawn as the Steve fallback - until it resolves, or forever if it
 * never does).
 */
@Lwjgl3Aware
public final class VoiceSkinIcons {

    // Vanilla 1.7.10's ImageBufferDownload flattens every skin onto 64x32, but GTNH patches the pipeline to
    // preserve modern 64x64 skins, so the bound texture's height cannot be assumed - draw() queries the actual
    // dimensions of the bound texture and normalizes against those. The face/hat blocks sit at the same
    // 64x-layout coordinates in both formats (and scale uniformly for HD skins), so only the aspect ratio
    // matters.
    private static final float SKIN_TEX_WIDTH = 64.0F;
    private static final float FACE_U = 8.0F;
    private static final float FACE_V = 8.0F;
    private static final float HAT_U = 40.0F;
    private static final float HAT_V = 8.0F;

    // The face/hat regions in the skin texture are always an 8x8 pixel block, regardless of what size the icon
    // is drawn at on screen - this is the *source* region size, not the destination draw size.
    private static final int SKIN_REGION_SIZE = 8;

    // The glGet width/height queries return constants per skin texture, so the derived tileHeight is cached
    // per ResourceLocation. draw() is only ever called on the render thread, so a plain HashMap is fine.
    private static final Map<ResourceLocation, Float> TILE_HEIGHT_CACHE = new HashMap<>();

    private VoiceSkinIcons() {}

    public static ResourceLocation resolveSkin(Minecraft mc, UUID uuid, String label) {
        ResourceLocation loaded = loadedPlayerSkin(mc, uuid);
        if (loaded != null) return loaded;

        ResourceLocation cached = HeadIconCache.getInstance()
            .get(uuid, label);
        return cached != null ? cached : AbstractClientPlayer.locationStevePng;
    }

    private static ResourceLocation loadedPlayerSkin(Minecraft mc, UUID uuid) {
        if (mc.theWorld == null) return null;

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!(player instanceof AbstractClientPlayer)) continue;
            if (!uuid.equals(
                player.getGameProfile()
                    .getId()))
                continue;

            return ((AbstractClientPlayer) player).getLocationSkin();
        }

        return null;
    }

    /**
     * Draws a {@code size}x{@code size} face+hat head icon at {@code (x, y)} for {@code uuid}.
     */
    public static void draw(Minecraft mc, UUID uuid, String label, int x, int y, int size) {
        draw(mc, uuid, label, x, y, size, 1.0F);
    }

    /**
     * Like {@link #draw(Minecraft, UUID, String, int, int, int)} but modulated by {@code alpha} (0..1), for
     * fading HUD rows. Leaves GL_BLEND enabled and the GL color reset to opaque white on return.
     */
    public static void draw(Minecraft mc, UUID uuid, String label, int x, int y, int size, float alpha) {
        ResourceLocation skin = resolveSkin(mc, uuid, label);

        mc.getTextureManager()
            .bindTexture(skin);
        Float cachedTileHeight = TILE_HEIGHT_CACHE.get(skin);
        float tileHeight;
        if (cachedTileHeight != null) {
            tileHeight = cachedTileHeight;
        } else {
            int texWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int texHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (texWidth > 0) {
                tileHeight = SKIN_TEX_WIDTH * texHeight / texWidth;
                TILE_HEIGHT_CACHE.put(skin, tileHeight);
            } else {
                // No caching for the fallback: retry once the texture actually loads.
                tileHeight = SKIN_TEX_WIDTH / 2.0F;
            }
        }
        GL11.glColor4f(1.0F, 1.0F, 1.0F, alpha);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        Gui.func_152125_a(
            x,
            y,
            FACE_U,
            FACE_V,
            SKIN_REGION_SIZE,
            SKIN_REGION_SIZE,
            size,
            size,
            SKIN_TEX_WIDTH,
            tileHeight);
        Gui.func_152125_a(
            x,
            y,
            HAT_U,
            HAT_V,
            SKIN_REGION_SIZE,
            SKIN_REGION_SIZE,
            size,
            size,
            SKIN_TEX_WIDTH,
            tileHeight);

        // GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
