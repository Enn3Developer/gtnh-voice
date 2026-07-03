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
 * Logs on entry and catches everything: FML's custom-channel pipeline does not reliably surface
 * exceptions thrown from {@link IMessageHandler#onMessage} to the console (unlike the main
 * connection pipeline), so a silent failure here would otherwise vanish without a trace.
 */
public class ClientHelloServerHandler implements IMessageHandler<ClientHelloPacket, IMessage> {

    @Override
    public IMessage onMessage(ClientHelloPacket message, MessageContext ctx) {
        GtnhVoice.LOG.info(
            "Received ClientHelloPacket (protocolVersion={}, modVersion={})",
            message.getProtocolVersion(),
            message.getModVersion());
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
