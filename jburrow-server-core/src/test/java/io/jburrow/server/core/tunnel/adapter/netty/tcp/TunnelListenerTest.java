package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.nio.NioIoHandler;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TunnelListenerTest {

    private static final WriteBufferWaterMark WATERMARKS = new WriteBufferWaterMark(32 * 1024, 128 * 1024);
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private EventLoopGroup group;

    @AfterEach
    void stop() {
        if (group != null) {
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    void visitorsRunOnTheListenersEventLoopWithTheirOptions() throws Exception {
        EventLoop loop = loop(NioIoHandler.newFactory());
        CompletableFuture<Channel> accepted = new CompletableFuture<>();
        TunnelListener listener =
                TunnelListener.bind(loop, new InetSocketAddress(LOOPBACK, 0), WATERMARKS, recorder(accepted));
        listener.bindFuture().syncUninterruptibly();

        try (Socket visitor = new Socket(LOOPBACK, port(listener))) {
            Channel channel = accepted.get(5, TimeUnit.SECONDS);
            assertThat(channel.eventLoop()).isSameAs(loop);
            assertThat(channel.config().isAutoRead()).isFalse();
            assertThat(channel.config().getOption(ChannelOption.ALLOW_HALF_CLOSURE))
                    .isTrue();
            assertThat(channel.config().getOption(ChannelOption.TCP_NODELAY)).isTrue();
            assertThat(channel.config().getWriteBufferWaterMark().high()).isEqualTo(WATERMARKS.high());
        } finally {
            listener.close().syncUninterruptibly();
        }
    }

    @Test
    void noSecondSocketCanShareTheTunnelPortWhileItIsOpen() throws Exception {
        TunnelListener listener = TunnelListener.bind(
                loop(NioIoHandler.newFactory()), new InetSocketAddress(LOOPBACK, 0), WATERMARKS, recorder(null));
        listener.bindFuture().syncUninterruptibly();
        int port = port(listener);

        try (ServerSocket hijacker = new ServerSocket()) {
            hijacker.setReuseAddress(true);
            assertThatThrownBy(() -> hijacker.bind(new InetSocketAddress(LOOPBACK, port)))
                    .isInstanceOf(BindException.class);
        } finally {
            listener.close().syncUninterruptibly();
        }
    }

    @Test
    void thePortIsFreeOnceTheCloseCompletes() throws Exception {
        TunnelListener listener = TunnelListener.bind(
                loop(NioIoHandler.newFactory()), new InetSocketAddress(LOOPBACK, 0), WATERMARKS, recorder(null));
        listener.bindFuture().syncUninterruptibly();
        int port = port(listener);

        listener.close().syncUninterruptibly();

        try (ServerSocket next = new ServerSocket()) {
            next.setReuseAddress(true);
            next.bind(new InetSocketAddress(LOOPBACK, port));
            assertThat(next.isBound()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"nio", "epoll"})
    void aPortInUseOnTheHostIsRecognisedOnEveryTransport(String transport) throws Exception {
        IoHandlerFactory handlers;
        if (transport.equals("epoll")) {
            assumeTrue(Epoll.isAvailable(), "epoll is unavailable on this host");
            handlers = EpollIoHandler.newFactory();
        } else {
            handlers = NioIoHandler.newFactory();
        }
        EventLoop loop = loop(handlers);
        try (ServerSocket occupant = new ServerSocket(0, 50, LOOPBACK)) {
            ChannelFuture bind = TunnelListener.bind(
                            loop, new InetSocketAddress(LOOPBACK, occupant.getLocalPort()), WATERMARKS, recorder(null))
                    .bindFuture()
                    .awaitUninterruptibly();

            assertThat(bind.isSuccess()).isFalse();
            assertThat(NettyTunnelListeners.addressInUse(bind.cause()))
                    .as("%s", bind.cause())
                    .isTrue();
            assertThat(bind.channel().closeFuture().await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private EventLoop loop(IoHandlerFactory handlers) {
        group = new MultiThreadIoEventLoopGroup(1, handlers);
        return group.next();
    }

    private static int port(TunnelListener listener) {
        return ((InetSocketAddress) listener.bindFuture().channel().localAddress()).getPort();
    }

    private static ChannelInboundHandlerAdapter recorder(CompletableFuture<Channel> accepted) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                if (accepted != null) {
                    accepted.complete(ctx.channel());
                }
            }

            @Override
            public boolean isSharable() {
                return true;
            }
        };
    }
}
