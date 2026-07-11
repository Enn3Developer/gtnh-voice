package com.enn3developer.gtnhvoice.client;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.network.VoiceGroupTablePacket;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Logs on entry and catches everything: FML's custom-channel pipeline does not reliably surface
 * exceptions thrown from {@link IMessageHandler#onMessage} to the console, so a silent failure
 * here would otherwise vanish without a trace.
 */
public class VoiceGroupTableClientHandler implements IMessageHandler<VoiceGroupTablePacket, IMessage> {

    @Override
    public IMessage onMessage(VoiceGroupTablePacket message, MessageContext ctx) {
        GtnhVoice.LOG.info(
            "Received VoiceGroupTablePacket (protocolVersion={}, groups={})",
            message.getProtocolVersion(),
            message.getGroupTable()
                .size());
        try {
            VoiceClientManager.getInstance()
                .handleGroupTable(message);
        } catch (Throwable t) {
            GtnhVoice.LOG.error("Failed to handle VoiceGroupTablePacket", t);
        }
        return null;
    }
}
