/*
 * Adapted from Plasmo Voice (su.plo.voice.server.socket.NettyUdpServer), licensed under LGPL-3.0.
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
 * Minimal, MC-independent UDP transport server: binds a datagram channel, decodes incoming
 * datagrams via {@link com.enn3developer.gtnhvoice.core.proto.packets.udp.PacketUdpCodec}, and
 * reports them to a {@link UdpPacketListener}. Deliberately has no notion of per-client
 * connections, secrets registries, or keepalives - that belongs to whatever wires this up above
 * core.
 */
public final class UdpTransportServer {

    private static final Logger LOGGER = LogManager.getLogger(UdpTransportServer.class);

    private final EventLoopGroup loopGroup = new NioEventLoopGroup();
    private final UdpPacketListener listener;

    private Channel channel;
    private volatile boolean closed;

    public UdpTransportServer(@NotNull UdpPacketListener listener) {
        this.listener = listener;
    }

    public InetSocketAddress bind(@NotNull String host, int port) throws InterruptedException {
        if (closed) throw new IllegalStateException("Server is closed and cannot be reused");

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(loopGroup)
            .channel(NioDatagramChannel.class)
            .handler(new ChannelInitializer<NioDatagramChannel>() {

                @Override
                protected void initChannel(@NotNull NioDatagramChannel ch) {
                    ChannelPipeline pipeline = ch.pipeline();

                    pipeline.addLast("decoder", new NettyPacketUdpDecoder(PacketDirection.SERVER));
                    pipeline.addLast("handler", new InboundHandler());
                    pipeline.addLast("exception_handler", new NettyExceptionHandler());
                }
            });

        ChannelFuture channelFuture = bootstrap.bind(host, port)
            .sync();
        this.channel = channelFuture.channel();

        InetSocketAddress boundAddress = (InetSocketAddress) channel.localAddress();
        LOGGER.info("UDP transport server bound on {}", boundAddress);

        return boundAddress;
    }

    /**
     * Sends a packet to an explicit recipient address. Unlike {@code UdpTransportClient}, the
     * server has no single "remote" - every send targets whichever client last identified itself
     * over this session's secret.
     */
    public void send(@NotNull Packet<?> packet, @NotNull UUID secret, @NotNull Encryption encryption,
        @NotNull InetSocketAddress recipient) {
        if (channel == null) throw new IllegalStateException("Server is not bound");

        byte[] encoded = PacketUdpCodec.encode(packet, secret, encryption);
        if (encoded == null) return;

        ByteBuf buf = Unpooled.wrappedBuffer(encoded);
        channel.writeAndFlush(new DatagramPacket(buf, recipient));
    }

    public void close() {
        if (closed) return;
        closed = true;

        if (channel != null) channel.close();
        loopGroup.shutdownGracefully();

        LOGGER.info("UDP transport server closed");
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
