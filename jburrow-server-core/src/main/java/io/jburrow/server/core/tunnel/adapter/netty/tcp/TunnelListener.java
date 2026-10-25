package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.WriteBufferWaterMark;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * One tunnel's public listener. Its event loop is its session's, as parent and child group, so every visitor
 * connection it accepts runs on the same thread as the session (ADR-0008).
 *
 * <p>{@code SO_REUSEADDR} is on so a port with connections in {@code TIME_WAIT} from its previous tunnel can be bound
 * again. {@code SO_REUSEPORT} is never set: it would let a second listener share the port and receive its visitors.
 */
final class TunnelListener {

    static final int BACKLOG = 128;

    /** The largest visitor read, and so the largest {@code DATA} payload: one socket read is exactly one frame. */
    static final int MAX_READ = 65_536;

    private static final int MIN_READ = 64;
    private static final int INITIAL_READ = 2_048;

    private final ChannelFuture bind;

    private TunnelListener(ChannelFuture bind) {
        this.bind = bind;
    }

    /**
     * Starts binding {@code address} on {@code loop}. Visitor connections start with {@code autoRead} off, half-closure
     * allowed and {@code TCP_NODELAY} on, and {@code visitorHandler} is added to each.
     */
    static TunnelListener bind(
            EventLoop loop,
            InetSocketAddress address,
            WriteBufferWaterMark visitorWatermarks,
            ChannelHandler visitorHandler) {
        Objects.requireNonNull(loop, "loop");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(visitorWatermarks, "visitorWatermarks");
        Objects.requireNonNull(visitorHandler, "visitorHandler");
        ChannelFuture bind = new ServerBootstrap()
                .group(loop, loop)
                .channel(EdgeTransport.serverChannelFor(loop))
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_BACKLOG, BACKLOG)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.ALLOW_HALF_CLOSURE, true)
                .childOption(ChannelOption.AUTO_READ, false)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, visitorWatermarks)
                .childOption(
                        ChannelOption.RECVBUF_ALLOCATOR,
                        new AdaptiveRecvByteBufAllocator(MIN_READ, INITIAL_READ, MAX_READ))
                .childHandler(visitorHandler)
                .bind(address);
        return new TunnelListener(bind);
    }

    /** Completes when the bind does; a failed bind leaves the listener closed. */
    ChannelFuture bindFuture() {
        return bind;
    }

    /** Closes the listener, whether its bind has completed or not. Visitor connections it accepted stay open. */
    ChannelFuture close() {
        return bind.channel().close();
    }
}
