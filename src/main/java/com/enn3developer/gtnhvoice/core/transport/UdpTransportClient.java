/*
 * Adapted from Plasmo Voice (su.plo.voice.client.socket.NettyUdpClient), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.transport;

import java.net.InetSocketAddress;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.api.encryption.Encryption;
import com.enn3developer.gtnhvoice.core.proto.packets.Packet;
import com.enn3developer.gtnhvoice.core.proto.packets.PacketDirection;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdpCodec;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

/**
 * Minimal, MC-independent UDP transport client: opens a datagram channel to a remote address,
 * sends packets encoded via {@link PacketUdpCodec}, and reports decoded inbound packets to a
 * {@link UdpPacketListener}. Deliberately has no notion of connection state, secrets registries,
 * or keepalives - that belongs to whatever wires this up above core.
 */
public final class UdpTransportClient {

    private static final Logger LOGGER = LogManager.getLogger(UdpTransportClient.class);

    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final UdpPacketListener listener;

    private Channel channel;
    private volatile boolean closed;

    public UdpTransportClient(@NotNull UdpPacketListener listener) {
        this.listener = listener;
    }

    public void connect(@NotNull String host, int port) throws InterruptedException {
        if (closed) throw new IllegalStateException("Client is closed and cannot be reused");

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
            .channel(NioDatagramChannel.class)
            .handler(new ChannelInitializer<NioDatagramChannel>() {

                @Override
                protected void initChannel(@NotNull NioDatagramChannel ch) {
                    ChannelPipeline pipeline = ch.pipeline();

                    pipeline.addLast("decoder", new NettyPacketUdpDecoder(PacketDirection.CLIENT));
                    pipeline.addLast("handler", new InboundHandler());
                    pipeline.addLast("exception_handler", new NettyExceptionHandler());
                }
            });

        ChannelFuture channelFuture = bootstrap.connect(host, port)
            .sync();
        this.channel = channelFuture.channel();

        LOGGER.info("UDP transport client connected to {}:{}", host, port);
    }

    public void send(@NotNull Packet<?> packet, @NotNull UUID sessionId, @NotNull Encryption encryption) {
        if (channel == null) throw new IllegalStateException("Client is not connected");

        byte[] encoded = PacketUdpCodec.encode(packet, sessionId, encryption);
        if (encoded == null) return;

        ByteBuf buf = Unpooled.wrappedBuffer(encoded);
        channel.writeAndFlush(new DatagramPacket(buf, (InetSocketAddress) channel.remoteAddress()));
    }

    public void close() {
        if (closed) return;
        closed = true;

        if (channel != null) channel.close();
        workerGroup.shutdownGracefully();

        LOGGER.info("UDP transport client closed");
    }

    private final class InboundHandler extends SimpleChannelInboundHandler<NettyPacketUdp> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, @NotNull NettyPacketUdp packet) {
            InetSocketAddress sender = packet.getDatagramPacket()
                .sender();
            listener.onPacket(packet.getPacketUdp(), sender);
        }
    }
}
