package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.DuplexChannel;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * One visitor connection to a tunnel, and the stream that carries it once admitted. Turns the connection's events into
 * calls on its session's {@link StreamRelay}, which makes every decision, and holds the stream's own counters.
 *
 * <p>Confined to the session's event loop, like everything else of the session.
 */
final class VisitorHandler extends ChannelInboundHandlerAdapter {

    /** Before admission; never a data stream id. */
    static final int NO_STREAM = 0;

    private final StreamRelay relay;
    private final Tunnel tunnel;

    private DuplexChannel channel;
    private int streamId = NO_STREAM;
    private long openedNanos;
    private long bytesIn;
    private long bytesOut;
    private long reportedIn;
    private long reportedOut;
    private boolean flushPending;

    VisitorHandler(StreamRelay relay, Tunnel tunnel) {
        this.relay = Objects.requireNonNull(relay, "relay");
        this.tunnel = Objects.requireNonNull(tunnel, "tunnel");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        channel = (DuplexChannel) ctx.channel();
        relay.onVisitorConnected(this);
        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf bytes) {
            relay.onVisitorData(this, bytes);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        relay.onVisitorReadComplete();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof ChannelInputShutdownEvent) {
            relay.onVisitorInputShutdown(this);
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        relay.onVisitorWritabilityChanged(this, ctx.channel().isWritable());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        relay.onVisitorFailed(this, cause);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        relay.onVisitorInactive(this);
    }

    Tunnel tunnel() {
        return tunnel;
    }

    DuplexChannel channel() {
        return channel;
    }

    InetSocketAddress remoteAddress() {
        return (InetSocketAddress) channel.remoteAddress();
    }

    int streamId() {
        return streamId;
    }

    boolean hasStream() {
        return streamId != NO_STREAM;
    }

    void opened(int streamId, long nowNanos) {
        this.streamId = streamId;
        this.openedNanos = nowNanos;
    }

    long openedNanos() {
        return openedNanos;
    }

    void countIn(long bytes) {
        bytesIn += bytes;
    }

    void countOut(long bytes) {
        bytesOut += bytes;
    }

    /** Payload bytes from this visitor towards the client. */
    long bytesIn() {
        return bytesIn;
    }

    /** Payload bytes from the client towards this visitor. */
    long bytesOut() {
        return bytesOut;
    }

    /** Inbound bytes not yet reported to metrics, marked as reported. */
    long takeUnreportedIn() {
        long unreported = bytesIn - reportedIn;
        reportedIn = bytesIn;
        return unreported;
    }

    /** Outbound bytes not yet reported to metrics, marked as reported. */
    long takeUnreportedOut() {
        long unreported = bytesOut - reportedOut;
        reportedOut = bytesOut;
        return unreported;
    }

    /** Marks the connection as written in the current read batch; false if it already was. */
    boolean markFlushPending() {
        if (flushPending) {
            return false;
        }
        flushPending = true;
        return true;
    }

    void flush() {
        flushPending = false;
        channel.flush();
    }
}
