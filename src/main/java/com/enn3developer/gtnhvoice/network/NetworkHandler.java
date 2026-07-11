package com.enn3developer.gtnhvoice.network;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.client.ServerHelloClientHandler;
import com.enn3developer.gtnhvoice.client.ServerRejectClientHandler;
import com.enn3developer.gtnhvoice.client.VoiceGroupTableClientHandler;
import com.enn3developer.gtnhvoice.client.VoiceGroupUpdateClientHandler;
import com.enn3developer.gtnhvoice.client.VoiceRosterSnapshotClientHandler;
import com.enn3developer.gtnhvoice.client.VoiceRosterUpdateClientHandler;
import com.enn3developer.gtnhvoice.server.ClientHelloServerHandler;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * Registers the reliable control channel ("gtnhvoice") and its handshake messages. Called from
 * {@link com.enn3developer.gtnhvoice.CommonProxy#preInit} so both physical sides register
 * identically - the {@code Side} passed to {@code registerMessage} controls which physical side
 * actually installs the handler in its pipeline, not which side runs this method.
 */
public final class NetworkHandler {

    public static final SimpleNetworkWrapper WRAPPER = NetworkRegistry.INSTANCE.newSimpleChannel(VoiceProtocol.CHANNEL);

    /**
     * The one and only serverbound discriminator on this channel (ClientHello). Every other
     * discriminator is clientbound. {@code MixinFMLIndexedMessageToMessageCodec} uses this to drop
     * wrong-side packets before they decode, so keep it in sync with the {@code id} assignment below.
     */
    public static final int SERVERBOUND_DISCRIMINATOR = 0;

    private static boolean initialized;

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        int id = 0;
        WRAPPER.registerMessage(ClientHelloServerHandler.class, ClientHelloPacket.class, id++, Side.SERVER);
        WRAPPER.registerMessage(ServerHelloClientHandler.class, ServerHelloPacket.class, id++, Side.CLIENT);
        WRAPPER.registerMessage(ServerRejectClientHandler.class, ServerRejectPacket.class, id++, Side.CLIENT);
        WRAPPER.registerMessage(
            VoiceRosterSnapshotClientHandler.class,
            VoiceRosterSnapshotPacket.class,
            id++,
            Side.CLIENT);
        WRAPPER.registerMessage(VoiceRosterUpdateClientHandler.class, VoiceRosterUpdatePacket.class, id++, Side.CLIENT);
        WRAPPER.registerMessage(VoiceGroupUpdateClientHandler.class, VoiceGroupUpdatePacket.class, id++, Side.CLIENT);
        WRAPPER.registerMessage(VoiceGroupTableClientHandler.class, VoiceGroupTablePacket.class, id++, Side.CLIENT);

        GtnhVoice.LOG.info(
            "Registered {} control-channel messages on '{}' (hasChannel CLIENT={} SERVER={})",
            id,
            VoiceProtocol.CHANNEL,
            NetworkRegistry.INSTANCE.hasChannel(VoiceProtocol.CHANNEL, Side.CLIENT),
            NetworkRegistry.INSTANCE.hasChannel(VoiceProtocol.CHANNEL, Side.SERVER));
    }

    private NetworkHandler() {}
}
