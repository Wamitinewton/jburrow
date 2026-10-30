package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes a connection that doesn't get its next request through in time. Armed with {@code request-timeout} when the
 * connection opens; the {@link HostDispatcher} disarms it when an exchange begins and re-arms it with
 * {@code keep-alive-timeout} when one ends, and the upgrade removes it. A deadline rather than a read timeout, which a
 * client sending one byte at a time would reset for ever.
 */
final class RequestDeadline extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(RequestDeadline.class);

    private final long firstRequestNanos;
    private ChannelHandlerContext ctx;
    private ScheduledFuture<?> deadline;
    private boolean started;

    RequestDeadline(long firstRequestNanos) {
        this.firstRequestNanos = firstRequestNanos;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        if (ctx.channel().isActive()) {
            start();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        start();
        ctx.fireChannelActive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        disarm();
    }

    /** Replaces any running deadline with one {@code timeoutNanos} from now. */
    void arm(long timeoutNanos) {
        disarm();
        deadline = ctx.executor()
                .schedule(
                        () -> {
                            log.debug(
                                    "Request deadline passed: client {}",
                                    ClientAddressResolver.clientAddress(ctx.channel())
                                            .getHostAddress());
                            ctx.close();
                        },
                        timeoutNanos,
                        TimeUnit.NANOSECONDS);
    }

    void disarm() {
        if (deadline != null) {
            deadline.cancel(false);
            deadline = null;
        }
    }

    private void start() {
        if (!started) {
            started = true;
            arm(firstRequestNanos);
        }
    }
}
