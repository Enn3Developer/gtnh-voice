package com.enn3developer.gtnhvoice.server;

import net.minecraft.entity.player.EntityPlayerMP;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.network.ClientHelloPacket;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Thin dispatch to {@link VoiceServerManager}. Safe to run directly on the Netty IO thread: the
 * session maps are concurrent, the only entity access is an immutable {@link
 * net.minecraft.entity.player.EntityPlayerMP#getGameProfile()} read, and sending packets/chat is
 * itself thread-safe (it just enqueues onto the player's network channel).
 * <p>
 * Catches everything but deliberately does NOT log the (remote-controlled) hello on entry: this runs
 * on the Netty IO thread before {@link VoiceServerManager}'s per-player rate limiter, so a per-hello log
 * here would run ahead of the gate. {@link VoiceServerManager#handleClientHello} logs the hello only
 * after the limiter passes. The catch stays because FML's custom-channel pipeline does not reliably
 * surface exceptions thrown from {@link IMessageHandler#onMessage} to the console (unlike the main
 * connection pipeline), so a silent failure here would otherwise vanish without a trace.
 */
public class ClientHelloServerHandler implements IMessageHandler<ClientHelloPacket, IMessage> {

    @Override
    public IMessage onMessage(ClientHelloPacket message, MessageContext ctx) {
        // No logging of the remote-controlled hello here: it runs on the Netty IO thread ahead of the
        // HelloRateLimiter, so any per-hello log would pile up before the gate can drop it (see finding on
        // this class). VoiceServerManager logs the hello only after tryAcquire passes. The try/catch
        // stays: FML's custom-channel pipeline swallows exceptions from onMessage, so we surface them.
        try {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            VoiceServerManager.getInstance()
                .handleClientHello(player, message);
        } catch (Throwable t) {
            GtnhVoice.LOG.error("Failed to handle ClientHelloPacket", t);
        }
        return null;
    }
}
