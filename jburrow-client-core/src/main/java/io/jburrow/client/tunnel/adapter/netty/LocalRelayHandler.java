package io.jburrow.client.tunnel.adapter.netty;

import io.jburrow.client.tunnel.domain.ActiveTunnel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
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
 * state: the {@code DATA} held while the connection is being made, the stream's counters, and the state its
 * {@link ReadGate} keeps for it.
 *
 * <p>The {@code DATA} held while connecting has no limit of its own: the client's stream window bounds it, since the
 * server can't send more until the client releases something, and nothing is released before the connection exists.
 *
 * <p>Each read is sized by the gate: at most what the stream may send right now, so a read becomes one {@code DATA}
 * frame. What a read takes beyond that waits in the carry slot, owned here until it is sent or released.
 *
 * <p>Added to its channel only once the connection is made. Confined to the session's event loop.
 */
final class LocalRelayHandler extends ChannelInboundHandlerAdapter {

    private final LocalConnector connector;
    private final int streamId;
    private final ActiveTunnel tunnel;
    private final long openedNanos;
    private final ArrayDeque<ByteBuf> pending = new ArrayDeque<>();

    private Future<Channel> dialing;
    private DuplexChannel channel;
    private boolean shutdownOutputOnConnect;
    private boolean flushPending;
    private long bytesIn;
    private long bytesOut;

    private ByteBuf carry;
    private boolean closeAfterCarry;
    private ReadGate.Wait waiting;
    private int share;

    LocalRelayHandler(LocalConnector connector, int streamId, ActiveTunnel tunnel, long openedNanos) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.streamId = streamId;
        this.tunnel = Objects.requireNonNull(tunnel, "tunnel");
        this.openedNanos = openedNanos;
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

    /** Holds a payload until the connection is made. Takes it over. */
    void hold(ByteBuf payload) {
        pending.add(payload);
    }

    /** The oldest payload held, which the caller now owns, or {@code null} if none is. */
    ByteBuf pollPending() {
        return pending.poll();
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

    /** The local target sent {@code FIN} while bytes were carried: its {@code CLOSE} goes out after them. */
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

    /** What this stream's reading waits for, or {@code null} while it isn't blocked. */
    ReadGate.Wait waiting() {
        return waiting;
    }

    void waiting(ReadGate.Wait wait) {
        waiting = wait;
    }
}
