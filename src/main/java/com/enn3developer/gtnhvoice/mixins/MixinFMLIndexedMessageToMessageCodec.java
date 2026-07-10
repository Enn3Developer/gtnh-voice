package com.enn3developer.gtnhvoice.mixins;

import com.enn3developer.gtnhvoice.network.NetworkHandler;
import com.enn3developer.gtnhvoice.network.VoiceProtocol;

import cpw.mods.fml.common.network.FMLIndexedMessageToMessageCodec;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.Side;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Side-scopes the decode step of FML's shared SimpleImpl codec, but ONLY for the {@code gtnhvoice}
 * control channel. Every other mod's channel is left untouched (the guard returns immediately when the
 * channel name doesn't match).
 *
 * <p>Why: {@code SimpleNetworkWrapper} uses a single {@code SimpleIndexedCodec} for the whole channel,
 * and {@link FMLIndexedMessageToMessageCodec#decode} runs {@code fromBytes} for ANY registered
 * discriminator regardless of the physical side. The {@code Side} passed to {@code registerMessage}
 * only chooses which side installs the downstream handler, not which side decodes. So an authenticated
 * client can send a clientbound discriminator (e.g. 1 = ServerHello) to the server; a truncated body
 * throws inside {@code decodeInto} on the main server thread, upstream of every gtnhvoice rate limiter,
 * and FML logs three stack traces per packet - a disk/tick-starvation flood.
 *
 * <p>Fix: peek the discriminator BEFORE {@code fromBytes} runs and drop it when it belongs to the wrong
 * side. On the server-received channel only the serverbound discriminator ({@link
 * NetworkHandler#SERVERBOUND_DISCRIMINATOR}) is decoded; on the client-received channel that serverbound
 * discriminator is dropped. Dropping (rather than catching the later exception) means the malformed
 * payload never allocates or reads anything - the wrong-side packet costs one absolute byte read.
 */
@Mixin(value = FMLIndexedMessageToMessageCodec.class, remap = false)
public abstract class MixinFMLIndexedMessageToMessageCodec {

    @Inject(method = "decode", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtnhvoice$dropWrongSideDiscriminator(
        ChannelHandlerContext ctx, FMLProxyPacket msg, List<Object> out, CallbackInfo ci) {

        String channelName = ctx.channel().attr(NetworkRegistry.FML_CHANNEL).get();
        if (!VoiceProtocol.CHANNEL.equals(channelName)) return;

        ByteBuf payload = msg.payload();
        if (payload.readableBytes() < 1) return;

        int discriminator = payload.getByte(payload.readerIndex()) & 0xFF;
        Side side = ctx.channel().attr(NetworkRegistry.CHANNEL_SOURCE).get();
        if (!gtnhvoice$isWrongSide(side, discriminator)) return;

        // Wrong-side discriminator: never call fromBytes. Leave `out` empty and cancel so the packet is
        // silently dropped - no allocation, no decode exception, no FML stack-trace storm.
        ci.cancel();
    }

    private static boolean gtnhvoice$isWrongSide(Side side, int discriminator) {
        boolean serverbound = discriminator == NetworkHandler.SERVERBOUND_DISCRIMINATOR;
        if (side == Side.SERVER) return !serverbound;
        if (side == Side.CLIENT) return serverbound;
        return false;
    }
}
