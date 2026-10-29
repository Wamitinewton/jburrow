package io.jburrow.client.tunnel.adapter.netty;

import io.jburrow.client.tunnel.domain.ClientSession;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.liveness.Liveness;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Keeps the session's {@link Liveness} ({@code docs/protocol.md} §11) with the intervals the server announced:
 * answers {@code PING}s, consumes {@code PONG}s, sends a {@code PING} after silence, and ends the session with
 * {@code HEARTBEAT_TIMEOUT} when the server has been silent for the idle timeout. Unlike the server, it answers every
 * {@code PING}: the client trusts its server not to flood it.
 *
 * <p>The clock starts with the server's {@code HELLO} ({@link #start}), which carries the intervals. Until then the
 * client has none, and its handshake deadline bounds the session instead (§8.3).
 *
 * <p>It keeps one timer. A frame received only moves the deadline later, so the timer isn't moved then: when it fires
 * early it finds nothing due and is set again. The exception is the frame that answers an outstanding {@code PING},
 * which brings the next {@code PING} forward.
 *
 * <p>Confined to the session's event loop.
 */
final class ClientLivenessHandler extends ChannelInboundHandlerAdapter {

    private final ClientSession session;
    private final ControlConnection connection;
    private final LongSupplier nanoTime;

    private ChannelHandlerContext ctx;
    private Liveness liveness;
    private ScheduledFuture<?> timer;
    private long idleTimeoutMillis;
    private long nextPingOpaque = 1;
    private boolean pingOutstanding;
    private boolean stopped;

    ClientLivenessHandler(ClientSession session, ControlConnection connection, LongSupplier nanoTime) {
        this.session = Objects.requireNonNull(session, "session");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    /** Starts the clock with the server's intervals, as if a frame had just been received. Does nothing once started. */
    void start(SessionLimits limits) {
        if (liveness != null || stopped) {
            return;
        }
        idleTimeoutMillis = limits.idleTimeoutMs();
        liveness = new Liveness(
                TimeUnit.MILLISECONDS.toNanos(limits.pingIntervalMs()),
                TimeUnit.MILLISECONDS.toNanos(limits.idleTimeoutMs()),
                nanoTime.getAsLong());
        reschedule();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (liveness == null || !(msg instanceof Frame frame) || session.isClosing()) {
            ctx.fireChannelRead(msg);
            return;
        }
        liveness.onFrameReceived(nanoTime.getAsLong());
        if (pingOutstanding) {
            pingOutstanding = false;
            reschedule();
        }
        try {
            switch (frame) {
                case Frame.Ping ping -> {
                    session.checkInbound(ping.type());
                    ctx.writeAndFlush(new Frame.Pong(ping.opaque()));
                }
                case Frame.Pong pong -> {
                    session.checkInbound(pong.type());
                    liveness.onPongReceived(pong.opaque());
                }
                default -> ctx.fireChannelRead(frame);
            }
        } catch (ProtocolException e) {
            connection.fail(e);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        stop();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        stop();
    }

    private void onTimer() {
        timer = null;
        if (stopped || session.isClosing()) {
            return;
        }
        long now = nanoTime.getAsLong();
        switch (liveness.check(now)) {
            case SEND_PING -> {
                long opaque = nextPingOpaque++;
                ctx.writeAndFlush(new Frame.Ping(opaque));
                liveness.onPingSent(now, opaque);
                pingOutstanding = true;
            }
            case DEAD -> {
                connection.fail(ProtocolException.session(
                        ErrorCode.HEARTBEAT_TIMEOUT,
                        "Nothing was received from the server for " + idleTimeoutMillis + " ms."));
                return;
            }
            case NONE -> {}
        }
        reschedule();
    }

    private void reschedule() {
        cancelTimer();
        if (stopped || ctx == null) {
            return;
        }
        long now = nanoTime.getAsLong();
        long deadline = liveness.nextDeadlineNanos(now);
        timer = ctx.executor().schedule(this::onTimer, deadline - now, TimeUnit.NANOSECONDS);
    }

    private void stop() {
        stopped = true;
        cancelTimer();
    }

    private void cancelTimer() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }
}
