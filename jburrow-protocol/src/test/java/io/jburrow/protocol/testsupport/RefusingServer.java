package io.jburrow.protocol.testsupport;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Answers every request with one fixed HTTP response and closes the connection: a server refusing the upgrade
 * ({@code docs/protocol.md} §3.5), or something in front of a server answering in its place. For testing a client's
 * reading of refusals.
 */
public final class RefusingServer implements AutoCloseable {

    private static final int MAX_REQUEST = 8_192;

    private final EventLoopGroup group;
    private final Channel listener;

    private RefusingServer(EventLoopGroup group, Channel listener) {
        this.group = group;
        this.listener = listener;
    }

    /**
     * Listens on an ephemeral loopback port and answers with {@code status}, {@code contentType} and {@code body}, plus
     * {@code Connection: close}.
     */
    public static RefusingServer start(int status, String contentType, String body) {
        Objects.requireNonNull(contentType, "contentType");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Channel listener = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(MAX_REQUEST))
                                .addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                                        FullHttpResponse response = new DefaultFullHttpResponse(
                                                HttpVersion.HTTP_1_1,
                                                HttpResponseStatus.valueOf(status),
                                                Unpooled.wrappedBuffer(bytes));
                                        response.headers()
                                                .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                                                .set(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
                                                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                                        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                                    }
                                });
                    }
                })
                .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .syncUninterruptibly()
                .channel();
        return new RefusingServer(group, listener);
    }

    /** The connect URL a client uses for this server. */
    public URI uri() {
        int port = ((InetSocketAddress) listener.localAddress()).getPort();
        return URI.create(
                "ws://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port + ScriptedPeer.CONNECT_PATH);
    }

    @Override
    public void close() {
        listener.close().awaitUninterruptibly();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
