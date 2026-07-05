package com.enn3developer.gtnhvoice.client;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.network.VoiceGroupUpdatePacket;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Logs on entry and catches everything: FML's custom-channel pipeline does not reliably surface
 * exceptions thrown from {@link IMessageHandler#onMessage} to the console, so a silent failure
 * here would otherwise vanish without a trace.
 */
public class VoiceGroupUpdateClientHandler implements IMessageHandler<VoiceGroupUpdatePacket, IMessage> {

    @Override
    public IMessage onMessage(VoiceGroupUpdatePacket message, MessageContext ctx) {
        GtnhVoice.LOG.info(
            "Received VoiceGroupUpdatePacket (protocolVersion={}, groupDisplayName={})",
            message.getProtocolVersion(),
            message.getGroupDisplayName());
        try {
            VoiceClientManager.getInstance()
                .handleGroupUpdate(message);
        } catch (Throwable t) {
            GtnhVoice.LOG.error("Failed to handle VoiceGroupUpdatePacket", t);
        }
        return null;
    }
}
