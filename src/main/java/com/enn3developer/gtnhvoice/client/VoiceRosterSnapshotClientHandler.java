package com.enn3developer.gtnhvoice.client;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.network.VoiceRosterSnapshotPacket;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Logs on entry and catches everything: FML's custom-channel pipeline does not reliably surface
 * exceptions thrown from {@link IMessageHandler#onMessage} to the console, so a silent failure
 * here would otherwise vanish without a trace.
 */
public class VoiceRosterSnapshotClientHandler implements IMessageHandler<VoiceRosterSnapshotPacket, IMessage> {

    @Override
    public IMessage onMessage(VoiceRosterSnapshotPacket message, MessageContext ctx) {
        GtnhVoice.LOG.info(
            "Received VoiceRosterSnapshotPacket (protocolVersion={}, entries={})",
            message.getProtocolVersion(),
            message.getRoster()
                .size());
        try {
            VoiceClientManager.getInstance()
                .handleRosterSnapshot(message);
        } catch (Throwable t) {
            GtnhVoice.LOG.error("Failed to handle VoiceRosterSnapshotPacket", t);
        }
        return null;
    }
}
