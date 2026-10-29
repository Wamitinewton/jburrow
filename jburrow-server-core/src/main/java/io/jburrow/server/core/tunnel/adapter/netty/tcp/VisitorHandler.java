package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import io.jburrow.protocol.adapter.netty.CreditBoundedRecvByteBufAllocator;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.DuplexChannel;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * One visitor connection to a tunnel, and the stream that carries it once admitted. Turns the connection's events into
 * calls on its session's {@link StreamRelay}, which makes every decision, and holds the stream's own counters and the
 * state its {@link ReadGate} keeps for it.
 *
 * <p>Each read is sized by the gate: at most what the stream may send right now, so a read becomes one {@code DATA}
 * frame. What a read takes beyond that waits in the carry slot, owned here until it is sent or released.
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

    private ByteBuf carry;
    private boolean closeAfterCarry;
    private ReadGate.Wait waiting;
    private int share;
    private long blockedSinceNanos;
    private long blockedNanos;

    VisitorHandler(StreamRelay relay, Tunnel tunnel) {
        this.relay = Objects.requireNonNull(relay, "relay");
        this.tunnel = Objects.requireNonNull(tunnel, "tunnel");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        channel = (DuplexChannel) ctx.channel();
        // autoRead is off until the stream opens, and Netty makes a channel's read handle on its first read, so this
        // allocator sizes every read.
        channel.config()
                .setRecvByteBufAllocator(
                        new CreditBoundedRecvByteBufAllocator(() -> relay.gate().sendable(this)));
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

    // The read gate's state.

    boolean hasCarry() {
        return carry != null;
    }

    /** Takes over {@code bytes}, behind whatever is already carried. */
    void carry(ByteBuf bytes) {
        if (carry == null) {
            carry = bytes;
            return;
        }
        if (!(carry instanceof CompositeByteBuf)) {
            carry = channel.alloc().compositeBuffer().addComponent(true, carry);
        }
        ((CompositeByteBuf) carry).addComponent(true, bytes);
    }

    int carried() {
        return carry == null ? 0 : carry.readableBytes();
    }

    /** The first {@code bytes} carried, or all of them if fewer; the caller owns the returned buffer. */
    ByteBuf takeCarry(int bytes) {
        if (bytes >= carry.readableBytes()) {
            ByteBuf all = carry;
            carry = null;
            return all;
        }
        return carry.readRetainedSlice(bytes);
    }

    void releaseCarry() {
        if (carry != null) {
            carry.release();
            carry = null;
        }
        closeAfterCarry = false;
    }

    /** The visitor sent {@code FIN} while bytes were carried: its {@code CLOSE} goes out after them. */
    void closeAfterCarry() {
        closeAfterCarry = true;
    }

    /** Whether a {@code CLOSE} was waiting behind the carry; it is the caller's to send now. */
    boolean takeCloseAfterCarry() {
        boolean close = closeAfterCarry;
        closeAfterCarry = false;
        return close;
    }

    /** The most its next read may take, its share of the session's credit; 0 for no cap beyond the credit. */
    int share() {
        return share;
    }

    void share(int bytes) {
        share = bytes;
    }

    /** What this visitor's reading waits for, or {@code null} while it isn't blocked. */
    ReadGate.Wait waiting() {
        return waiting;
    }

    void blocked(ReadGate.Wait wait, long nowNanos) {
        if (waiting == null) {
            blockedSinceNanos = nowNanos;
        }
        waiting = wait;
    }

    void unblocked(long nowNanos) {
        if (waiting != null) {
            blockedNanos += nowNanos - blockedSinceNanos;
            waiting = null;
        }
    }

    /** How long this stream has spent unable to send for want of credit or headroom, over the periods that ended. */
    long blockedNanos() {
        return blockedNanos;
    }
}
