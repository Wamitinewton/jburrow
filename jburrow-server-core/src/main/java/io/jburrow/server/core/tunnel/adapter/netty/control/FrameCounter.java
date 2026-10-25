package io.jburrow.server.core.tunnel.adapter.netty.control;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.FrameDirection;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.Objects;

/** Counts {@code jburrow_frames_total} for every frame a session receives or sends, next to the codec. */
@ChannelHandler.Sharable
final class FrameCounter extends ChannelDuplexHandler {

    private final TunnelMetrics metrics;

    FrameCounter(TunnelMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Frame frame) {
            metrics.frame(frame.type(), FrameDirection.RECEIVED);
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof Frame frame) {
            metrics.frame(frame.type(), FrameDirection.SENT);
        }
        ctx.write(msg, promise);
    }
}
