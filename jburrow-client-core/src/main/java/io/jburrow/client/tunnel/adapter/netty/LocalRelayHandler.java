package io.jburrow.client.tunnel.adapter.netty;

import io.jburrow.client.tunnel.domain.ActiveTunnel;
import io.jburrow.client.tunnel.domain.PendingData;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.DuplexChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * One stream and the local connection that carries it, the client's counterpart of the server's visitor handler. Turns
 * the connection's events into calls on {@link LocalConnector}, which makes every decision, and holds the stream's own
 * state: the {@code DATA} held while the connection is being made, and the stream's counters.
 *
 * <p>Added to its channel only once the connection is made. Confined to the session's event loop.
 */
final class LocalRelayHandler extends ChannelInboundHandlerAdapter {

    private final LocalConnector connector;
    private final int streamId;
    private final ActiveTunnel tunnel;
    private final long openedNanos;
    private final PendingData pendingBytes;
    private final ArrayDeque<ByteBuf> pending = new ArrayDeque<>();

    private Future<Channel> dialing;
    private DuplexChannel channel;
    private boolean shutdownOutputOnConnect;
    private boolean flushPending;
    private long bytesIn;
    private long bytesOut;

    LocalRelayHandler(
            LocalConnector connector, int streamId, ActiveTunnel tunnel, long openedNanos, PendingData pendingBytes) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.streamId = streamId;
        this.tunnel = Objects.requireNonNull(tunnel, "tunnel");
        this.openedNanos = openedNanos;
        this.pendingBytes = Objects.requireNonNull(pendingBytes, "pendingBytes");
    }

    // The local connection's events.

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf bytes) {
            connector.onLocalData(this, bytes);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        connector.onLocalReadComplete();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof ChannelInputShutdownEvent) {
            connector.onLocalInputShutdown(this);
        }
        ReferenceCountUtil.release(event);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        connector.onLocalWritabilityChanged(this);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        connector.onLocalFailed(this, cause);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        connector.onLocalInactive(this);
    }

    // The stream's state.

    int streamId() {
        return streamId;
    }

    ActiveTunnel tunnel() {
        return tunnel;
    }

    long openedNanos() {
        return openedNanos;
    }

    void dialing(Future<Channel> dialing) {
        this.dialing = dialing;
    }

    /** Gives up on the connection being made, if it is still being made. */
    void cancelDial() {
        if (dialing != null && !dialing.isDone()) {
            dialing.cancel(false);
        }
    }

    void connected(DuplexChannel channel) {
        this.channel = channel;
    }

    boolean isConnected() {
        return channel != null;
    }

    /** The local connection; null until it is made. */
    DuplexChannel channel() {
        return channel;
    }

    /** Holds a payload until the connection is made. Takes it over. True if this took the total over the limit. */
    boolean hold(ByteBuf payload) {
        pending.add(payload);
        return pendingBytes.add(payload.readableBytes());
    }

    boolean isPendingOverLimit() {
        return pendingBytes.isOverLimit();
    }

    /** Writes everything held, in order, and flushes. */
    void writePending() {
        for (ByteBuf payload = pending.poll(); payload != null; payload = pending.poll()) {
            channel.write(payload, channel.voidPromise());
        }
        pendingBytes.clear();
        channel.flush();
    }

    /** Drops everything held: the stream ended before the connection was made. */
    void releasePending() {
        for (ByteBuf payload = pending.poll(); payload != null; payload = pending.poll()) {
            payload.release();
        }
        pendingBytes.clear();
    }

    /** The server half-closed the stream before the connection was made: shut down its output once it is. */
    void shutdownOutputOnConnect() {
        shutdownOutputOnConnect = true;
    }

    boolean isShutdownOutputOnConnect() {
        return shutdownOutputOnConnect;
    }

    void countIn(long bytes) {
        bytesIn += bytes;
    }

    void countOut(long bytes) {
        bytesOut += bytes;
    }

    /** Payload bytes from the visitor towards the local target. */
    long bytesIn() {
        return bytesIn;
    }

    /** Payload bytes from the local target towards the visitor. */
    long bytesOut() {
        return bytesOut;
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
