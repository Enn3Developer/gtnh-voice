package com.enn3developer.gtnhvoice.client;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.network.ServerRejectPacket;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Logs on entry and catches everything: FML's custom-channel pipeline does not reliably surface
 * exceptions thrown from {@link IMessageHandler#onMessage} to the console, so a silent failure
 * here would otherwise vanish without a trace.
 */
public class ServerRejectClientHandler implements IMessageHandler<ServerRejectPacket, IMessage> {

    @Override
    public IMessage onMessage(ServerRejectPacket message, MessageContext ctx) {
        GtnhVoice.LOG.info(
            "Received ServerRejectPacket (serverProtocolVersion={}, reason={})",
            message.getServerProtocolVersion(),
            message.getReason());
        try {
            VoiceClientManager.getInstance()
                .handleServerReject(message);
        } catch (Throwable t) {
            GtnhVoice.LOG.error("Failed to handle ServerRejectPacket", t);
        }
        return null;
    }
}
