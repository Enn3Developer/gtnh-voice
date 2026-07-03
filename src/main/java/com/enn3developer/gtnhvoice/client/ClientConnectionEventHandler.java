package com.enn3developer.gtnhvoice.client;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import com.enn3developer.gtnhvoice.GtnhVoice;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * Drives {@link VoiceClientManager} from the client's connection lifecycle. Registers on {@code
 * FMLCommonHandler.instance().bus()}: like {@link com.enn3developer.gtnhvoice.client.CaptureKeybindHandler}'s
 * {@code TickEvent}, {@code FMLNetworkEvent} is an FML event posted on the FML bus, not the Forge
 * event bus. Must be public: FML's ASM event bus subscriber scanning throws {@link
 * IllegalAccessError} on non-public listener classes under lwjgl3ify.
 * <p>
 * {@code ClientHello} is NOT sent synchronously from {@link #onConnectedToServer}: Hodgepodge's own
 * {@code FMLIndexedMessageToMessageCodecHook} documents that "early handshake FMLProxyPackets don't
 * have the dispatcher field set to the channel dispatcher", and its fallback lookup is best-effort -
 * a packet sent at this exact instant can still silently vanish with the dispatcher left null. We
 * defer the actual send to the next client tick, by which point the dispatcher has always settled.
 */
public class ClientConnectionEventHandler {

    private volatile String pendingConnectHost;

    public void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void onConnectedToServer(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        pendingConnectHost = resolveHost(event);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        String host = pendingConnectHost;
        if (host == null) return;
        pendingConnectHost = null;

        VoiceClientManager.getInstance()
            .onConnectedToServer(host);
    }

    @SubscribeEvent
    public void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        pendingConnectHost = null;
        GtnhVoice.LOG.info("Disconnected from server, tearing down voice session");
        VoiceClientManager.getInstance()
            .onDisconnected();
    }

    private String resolveHost(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        if (event.isLocal) return "127.0.0.1";

        SocketAddress address = event.manager.getSocketAddress();
        if (address instanceof InetSocketAddress) {
            return ((InetSocketAddress) address).getAddress()
                .getHostAddress();
        }

        return "127.0.0.1";
    }
}
