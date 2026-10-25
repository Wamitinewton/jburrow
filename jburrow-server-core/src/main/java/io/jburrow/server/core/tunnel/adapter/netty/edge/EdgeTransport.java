package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.netty.channel.EventLoop;
import io.netty.channel.IoEventLoop;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/** The I/O transport the edge runs on. */
public enum EdgeTransport {
    /** epoll where Netty's native library loads, otherwise NIO. */
    AUTO,
    /** epoll, or fail to start. */
    EPOLL,
    NIO;

    /** The server channel type that can be registered on {@code loop}, one of the edge's worker event loops. */
    public static Class<? extends ServerChannel> serverChannelFor(EventLoop loop) {
        return loop instanceof IoEventLoop io && io.isIoType(EpollIoHandler.class)
                ? EpollServerSocketChannel.class
                : NioServerSocketChannel.class;
    }
}
