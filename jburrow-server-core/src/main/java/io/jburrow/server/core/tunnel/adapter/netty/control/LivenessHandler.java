package io.jburrow.server.core.tunnel.adapter.netty.control;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.liveness.Liveness;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.tunnel.domain.PingRateLimit;
import io.jburrow.server.core.tunnel.domain.SessionState;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Keeps one session's {@link Liveness} ({@code docs/protocol.md} §10): answers and rate-limits the client's
 * {@code PING}s, consumes {@code PONG}s, sends {@code PING} after silence, and ends the session with
 * {@code HEARTBEAT_TIMEOUT} when the client has been silent for the idle timeout.
 *
 * <p>The clock starts when the server's {@code HELLO} is sent ({@link #start()}): before that the client has no
 * intervals, and the handshake deadline bounds the session instead.
 *
 * <p>It keeps one timer at a time. A frame received only moves the deadline later, so the timer isn't moved then: when
 * it fires early it finds nothing due and is set again. The exception is the frame that answers an outstanding
 * {@code PING}, which brings the next {@code PING} forward.
 *
 * <p>Confined to the session's event loop.
 */
public final class LivenessHandler extends ChannelInboundHandlerAdapter {

    private final SessionContext session;
    private final SessionCloser closer;
    private final long pingIntervalNanos;
    private final long idleTimeoutNanos;
    private final LongSupplier nanoTime;

    private ChannelHandlerContext ctx;
    private Liveness liveness;
    private PingRateLimit pingRate;
    private ScheduledFuture<?> timer;
    private long nextPingOpaque = 1;
    private boolean pingOutstanding;
    private boolean stopped;

    LivenessHandler(
            SessionContext session,
            SessionCloser closer,
            Duration pingInterval,
            Duration idleTimeout,
            LongSupplier nanoTime) {
        this.session = Objects.requireNonNull(session, "session");
        this.closer = Objects.requireNonNull(closer, "closer");
        this.pingIntervalNanos = pingInterval.toNanos();
        this.idleTimeoutNanos = idleTimeout.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    /** Starts the clock, as if a frame had just been received. Does nothing once started. */
    void start() {
        if (liveness != null || stopped) {
            return;
        }
        long now = nanoTime.getAsLong();
        liveness = new Liveness(pingIntervalNanos, idleTimeoutNanos, now);
        pingRate = PingRateLimit.standard(now);
        reschedule();
    }

    /**
     * Stops the clock while the session's connection isn't read, for backpressure, and keeps sending {@code PING}s so
     * the client still hears from this server.
     */
    public void pause() {
        if (liveness != null) {
            liveness.pause();
            reschedule();
        }
    }

    /** Restarts the clock from zero once the connection is read again. */
    public void resume() {
        if (liveness != null) {
            liveness.resume(nanoTime.getAsLong());
            reschedule();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (liveness == null || !(msg instanceof Frame frame) || isClosing()) {
            ctx.fireChannelRead(msg);
            return;
        }
        long now = nanoTime.getAsLong();
        liveness.onFrameReceived(now);
        if (pingOutstanding) {
            pingOutstanding = false;
            reschedule();
        }
        try {
            switch (frame) {
                case Frame.Ping ping -> onPing(ping, now);
                case Frame.Pong pong -> {
                    session.session().checkInbound(pong.type());
                    liveness.onPongReceived(pong.opaque());
                }
                default -> ctx.fireChannelRead(frame);
            }
        } catch (ProtocolException e) {
            closer.fail(session, e);
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

    private void onPing(Frame.Ping ping, long now) {
        session.session().checkInbound(ping.type());
        if (!pingRate.tryAcquire(now)) {
            closer.fail(
                    session,
                    ProtocolException.session(
                            ErrorCode.PING_RATE_EXCEEDED, "PINGs were sent faster than this server answers them."));
            return;
        }
        ctx.writeAndFlush(new Frame.Pong(ping.opaque()));
    }

    private void onTimer() {
        timer = null;
        if (stopped || isClosing()) {
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
                closer.fail(
                        session,
                        ProtocolException.session(
                                ErrorCode.HEARTBEAT_TIMEOUT,
                                "Nothing was received for " + TimeUnit.NANOSECONDS.toMillis(idleTimeoutNanos)
                                        + " ms."));
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

    private boolean isClosing() {
        return session.session().state() == SessionState.CLOSING;
    }
}
