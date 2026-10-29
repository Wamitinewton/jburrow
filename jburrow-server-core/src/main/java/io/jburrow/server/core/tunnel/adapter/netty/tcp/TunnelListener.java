package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
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

    /**
     * A session admits up to 1,000 streams, so a burst of that many visitors waits in the accept queue. A full queue
     * drops a handshake's last ACK while the visitor already thinks it is connected, and it waits seconds for the
     * kernel's retries. The kernel caps this at {@code net.core.somaxconn}.
     */
    static final int BACKLOG = 1_024;

    private final ChannelFuture bind;

    private TunnelListener(ChannelFuture bind) {
        this.bind = bind;
    }

    /**
     * Starts binding {@code address} on {@code loop}. Visitor connections start with {@code autoRead} off, half-closure
     * allowed and {@code TCP_NODELAY} on, and {@code visitorHandler} is added to each. Each visitor's read allocator is
     * its own, set by its {@link VisitorHandler}, since it is sized by that visitor's credit.
     */
    static TunnelListener bind(EventLoop loop, InetSocketAddress address, ChannelHandler visitorHandler) {
        Objects.requireNonNull(loop, "loop");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(visitorHandler, "visitorHandler");
        ChannelFuture bind = new ServerBootstrap()
                .group(loop, loop)
                .channel(EdgeTransport.serverChannelFor(loop))
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_BACKLOG, BACKLOG)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.ALLOW_HALF_CLOSURE, true)
                .childOption(ChannelOption.AUTO_READ, false)
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
