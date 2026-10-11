package com.enn3developer.gtnhvoice.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.util.Vec3;

import com.enn3developer.gtnhvoice.client.source.VoiceSourceManager;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Publishes the local player's absolute eye position and look direction to the active session's
 * {@link VoiceSourceManager} every client tick, driving the shared AL listener. Mirrors the position-snapshot
 * discipline used server-side: the audio thread never reads live MC world state directly, only this published
 * snapshot. Must be public: FML's ASM event bus subscriber scanning throws {@link IllegalAccessError} on non-public
 * listener classes under lwjgl3ify.
 */
public class VoiceListenerTickHandler {

    public void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        VoiceSourceManager sourceManager = VoiceClientManager.getInstance()
            .getVoiceSourceManager();
        if (sourceManager == null) return;

        EntityClientPlayerMP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;

        Vec3 look = player.getLookVec();
        sourceManager.updateListener(
            player.posX,
            player.posY + player.getEyeHeight(),
            player.posZ,
            (float) look.xCoord,
            (float) look.yCoord,
            (float) look.zCoord);
    }
}
