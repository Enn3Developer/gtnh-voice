package com.enn3developer.gtnhvoice.client;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.Gui;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

/**
 * Shared face+hat head-icon resolution/drawing for the who's-talking HUD and the Players GUI: prefers a loaded
 * {@link AbstractClientPlayer}'s own (already-resolved) skin, falling back to {@link HeadIconCache} (which lazily
 * triggers a load and returns {@code null} - drawn as the Steve fallback - until it resolves, or forever if it
 * never does).
 */
public final class VoiceSkinIcons {

    // MC 1.7.10's SkinManager/ImageBufferDownload normalizes every downloaded skin onto a fixed 64x32 canvas
    // regardless of the source image's format (legacy 64x32 or the newer 64x64 layout) - see
    // ImageBufferDownload#parseUserSkin, which always allocates a 64x32 BufferedImage. So the face/hat UVs below
    // are always sampled against a 64x32 tile, never 64x64.
    private static final float SKIN_TEX_WIDTH = 64.0F;
    private static final float SKIN_TEX_HEIGHT = 32.0F;
    private static final float FACE_U = 8.0F;
    private static final float FACE_V = 8.0F;
    private static final float HAT_U = 40.0F;
    private static final float HAT_V = 8.0F;

    // The face/hat regions in the skin texture are always an 8x8 pixel block, regardless of what size the icon
    // is drawn at on screen - this is the *source* region size, not the destination draw size.
    private static final int SKIN_REGION_SIZE = 8;

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
        ResourceLocation skin = resolveSkin(mc, uuid, label);

        mc.getTextureManager()
            .bindTexture(skin);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
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
            SKIN_TEX_HEIGHT);
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
            SKIN_TEX_HEIGHT);

        GL11.glDisable(GL11.GL_BLEND);
    }
}
