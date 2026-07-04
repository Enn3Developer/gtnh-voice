package com.enn3developer.gtnhvoice.client;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.network.VoiceRosterUpdatePacket;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Logs on entry and catches everything: FML's custom-channel pipeline does not reliably surface
 * exceptions thrown from {@link IMessageHandler#onMessage} to the console, so a silent failure
 * here would otherwise vanish without a trace.
 */
public class VoiceRosterUpdateClientHandler implements IMessageHandler<VoiceRosterUpdatePacket, IMessage> {

    @Override
    public IMessage onMessage(VoiceRosterUpdatePacket message, MessageContext ctx) {
        GtnhVoice.LOG.info(
            "Received VoiceRosterUpdatePacket (protocolVersion={}, mode={}, playerUuid={}, playerName={})",
            message.getProtocolVersion(),
            message.getMode() == VoiceRosterUpdatePacket.MODE_ADD ? "ADD" : "REMOVE",
            message.getPlayerUuid(),
            message.getPlayerName());
        try {
            VoiceClientManager.getInstance()
                .handleRosterUpdate(message);
        } catch (Throwable t) {
            GtnhVoice.LOG.error("Failed to handle VoiceRosterUpdatePacket", t);
        }
        return null;
    }
}
