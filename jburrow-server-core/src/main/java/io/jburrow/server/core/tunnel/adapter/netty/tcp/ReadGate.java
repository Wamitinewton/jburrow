package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.Direction;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.FlowScope;
import io.jburrow.server.core.tunnel.domain.Session;
import io.jburrow.server.core.tunnel.domain.SessionState;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Decides when each visitor of one session is read, {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Visitor to
 * client: the read gate. A visitor is read only as far as its stream can send right now:
 *
 * <pre>sendable = min(stream credit, session credit, session channel headroom, 65,536)</pre>
 *
 * <p>A visitor whose own send takes {@code sendable} to 0 is paused at once. The others are paused lazily, the next
 * time they are read: such a read gives up at least one byte, which the visitor carries until credit returns. Only
 * visitors that actually have data pay for a pause, and idle ones are never touched.
 *
 * <p>A visitor blocked by its own stream's credit is resumed by that stream's {@code WINDOW_UPDATE}. One blocked by the
 * session's credit or headroom joins the line in {@link #blockedOnSession}. When stream 0's {@code WINDOW_UPDATE}
 * arrives or the session channel becomes writable again, visitors in line are resumed, in line order, until those
 * resumed may send all the session can. Neither event touches visitors that aren't waiting.
 *
 * <p>Visitors share the session's credit. Each visitor resumed from the line may take at most a share of the credit
 * there is, for its first read: an equal share, but never less than {@link #SHARE_FLOOR}. Which visitor the transport
 * reads first then doesn't decide who gets the credit, and NIO reads ready channels in no order the gate controls. The
 * floor keeps frames large when hundreds of visitors wait: below it, a grant would be split into frames of a few
 * hundred bytes. Visitors the credit doesn't cover stay paused, so they don't each give up a byte to a read that can't
 * be sent. The share lifts once that read is sent or held. Credit that resumed visitors leave unused, because they had
 * less to send, goes to the next ones in line after {@link #RECHECK_NANOS}, so none waits while credit sits unused.
 * A visitor keeps its place in line, through any number of resumes and one-byte pauses, until a read of its own is
 * sent; then it leaves, and rejoins at the back if that read spent what it may send, or if it was resumed from the
 * line and others there are still paused: one read per turn, so the transport's order of reads can't hand one visitor
 * the credit meant for those resumed with it. Fairness holds across grants, as turns rotate, rather than within each
 * one.
 *
 * <p>Confined to the session's event loop, so a credit check and the read it allows happen with no gap between them.
 */
final class ReadGate {

    /** The smallest share of the session's credit a resumed visitor is offered, unless less credit is left. */
    static final int SHARE_FLOOR = 16_384;

    /** How long after a partial resume the gate looks for credit resumed visitors didn't use. */
    static final long RECHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    /** Why a visitor isn't read. */
    enum Wait {
        /** Its stream's credit ran out. */
        STREAM,
        /** The session's credit ran out. */
        SESSION,
        /** The session channel's outbound buffer is at its high watermark. */
        HEADROOM,
        /** It had its turn at the session's credit, and others in line go first. */
        TURN
    }

    /** What the gate asks of the relay when carried bytes can go out. */
    interface Output {

        /** Sends {@code bytes} as {@code DATA} on the visitor's stream, spending its credit. Takes over the buffer. */
        void sendCarried(VisitorHandler visitor, ByteBuf bytes);

        /** Sends the {@code CLOSE} the visitor's {@code FIN} was holding back until its carried bytes went out. */
        void sendDeferredClose(VisitorHandler visitor);

        /** Flushes carried bytes sent outside a read batch, which no read-complete will flush. */
        void flushCarried();
    }

    private final Session session;
    private final Channel sessionChannel;
    private final TunnelMetrics metrics;
    private final Output output;
    private final LongSupplier nanoTime;
    /** The line: insertion order is the order visitors joined it, which is the order they are resumed in. */
    private final Set<VisitorHandler> blockedOnSession = new LinkedHashSet<>();

    private boolean recheckPending;

    ReadGate(Session session, Channel sessionChannel, TunnelMetrics metrics, Output output, LongSupplier nanoTime) {
        this.session = Objects.requireNonNull(session, "session");
        this.sessionChannel = Objects.requireNonNull(sessionChannel, "sessionChannel");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.output = Objects.requireNonNull(output, "output");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * How many bytes may be read from {@code visitor} and sent at once. 0 before its stream opens, once this side has
     * half-closed or closed it, and while the session is closing.
     */
    int sendable(VisitorHandler visitor) {
        if (!visitor.hasStream() || session.state() == SessionState.CLOSING) {
            return 0;
        }
        int credit = session.sendable(visitor.streamId());
        if (credit == 0) {
            return 0;
        }
        int sendable = Math.min(Math.min(credit, headroom()), FrameLimits.MAX_DATA_PAYLOAD);
        return visitor.share() > 0 ? Math.min(sendable, visitor.share()) : sendable;
    }

    /** The visitor's stream was opened and announced: read it if it may send. */
    void onOpened(VisitorHandler visitor) {
        reassess(visitor);
    }

    /**
     * A read from the visitor went out as {@code DATA}: it has had its turn and leaves the line. If that spent what it
     * may send, it stops being read now, and rejoins at the back if it waits for the session. If it was resumed from
     * the line and others there still wait, it rejoins at the back anyway, so the visitors resumed with it get their
     * reads before it takes a second.
     *
     * <p>A paused visitor can be read too, when the transport had already found it readable, so whatever it waited
     * for is over: left waiting, it would be out of the line and never resumed.
     */
    void afterSend(VisitorHandler visitor) {
        boolean resumedFromLine = visitor.share() > 0;
        leaveLine(visitor);
        visitor.share(0);
        if (sendable(visitor) == 0) {
            block(visitor);
        } else if (resumedFromLine && anyPausedInLine()) {
            setAutoRead(visitor, false);
            visitor.blocked(Wait.TURN, nanoTime.getAsLong());
            blockedOnSession.add(visitor);
            scheduleRecheck();
        } else if (!visitor.channel().isInputShutdown()) {
            setAutoRead(visitor, true);
        }
    }

    /** The visitor was read past what it may send: carry {@code bytes}, send what fits, pause it for the rest. */
    void hold(VisitorHandler visitor, ByteBuf bytes) {
        visitor.share(0);
        visitor.carry(bytes);
        reassess(visitor);
    }

    /** A {@code WINDOW_UPDATE} on the visitor's stream: its carried bytes go out first, then it is read again. */
    void onStreamCredit(VisitorHandler visitor) {
        reassess(visitor);
    }

    /** A {@code WINDOW_UPDATE} on stream 0. */
    void onSessionCredit() {
        resumeBlockedOnSession();
    }

    void onSessionWritable(boolean writable) {
        if (writable) {
            resumeBlockedOnSession();
        }
    }

    /** The visitor's stream ended: forget it, and release whatever it carried, which a reset doesn't send. */
    void onRemoved(VisitorHandler visitor) {
        leaveLine(visitor);
        visitor.releaseCarry();
    }

    /**
     * Resumes paused visitors in line, in line order, each with its share of the credit for its first read and keeping
     * its place until a read of its own is sent, until those resumed may send all the session can. Visitors it doesn't
     * reach stay paused, and are looked at again shortly in case the resumed ones leave credit unused.
     */
    private void resumeBlockedOnSession() {
        if (blockedOnSession.isEmpty() || sessionLimit() == 0) {
            return;
        }
        List<VisitorHandler> line = List.copyOf(blockedOnSession);
        int share = share(sessionLimit(), line.size());
        long promised = 0;
        for (VisitorHandler visitor : line) {
            int left = sessionLimit();
            if (left == 0) {
                return;
            }
            if (promised >= left) {
                scheduleRecheck();
                return;
            }
            // A visitor in line with nothing to wait for was resumed already and hasn't been read yet.
            if (blockedOnSession.contains(visitor) && visitor.waiting() != null) {
                visitor.share(share);
                reassess(visitor);
                promised += sendable(visitor);
            }
        }
    }

    /** Whether a visitor in line is paused; those that were resumed and not yet read are already being served. */
    private boolean anyPausedInLine() {
        for (VisitorHandler waiting : blockedOnSession) {
            if (waiting.waiting() != null) {
                return true;
            }
        }
        return false;
    }

    /** An equal share of {@code limit} among {@code visitors}, but at least the floor, or all of it if less. */
    static int share(int limit, int visitors) {
        return Math.max(Math.min(SHARE_FLOOR, limit), limit / visitors);
    }

    private void scheduleRecheck() {
        if (recheckPending) {
            return;
        }
        recheckPending = true;
        sessionChannel.eventLoop().schedule(this::recheck, RECHECK_NANOS, TimeUnit.NANOSECONDS);
    }

    private void recheck() {
        recheckPending = false;
        if (session.state() != SessionState.CLOSING) {
            resumeBlockedOnSession();
            output.flushCarried();
        }
    }

    /** What every visitor together may still send: the session's credit or the session channel's headroom. */
    private int sessionLimit() {
        return Math.min(session.sessionCredit(), headroom());
    }

    private void reassess(VisitorHandler visitor) {
        if (visitor.hasCarry()) {
            sendCarry(visitor);
            if (visitor.hasCarry()) {
                block(visitor);
                return;
            }
            if (visitor.takeCloseAfterCarry()) {
                leaveLine(visitor);
                output.sendDeferredClose(visitor);
                return;
            }
        }
        if (visitor.channel().isInputShutdown()) {
            leaveLine(visitor);
        } else if (sendable(visitor) > 0) {
            unblock(visitor);
            setAutoRead(visitor, true);
        } else {
            block(visitor);
        }
    }

    private void sendCarry(VisitorHandler visitor) {
        for (int sendable = sendable(visitor); sendable > 0 && visitor.hasCarry(); sendable = sendable(visitor)) {
            output.sendCarried(visitor, visitor.takeCarry(sendable));
        }
    }

    /**
     * Stops reading the visitor and records what it waits for, counting each pause once. One waiting for the session
     * joins the back of the line, unless it already has a place.
     */
    private void block(VisitorHandler visitor) {
        setAutoRead(visitor, false);
        Wait wait = waitOf(visitor);
        if (wait == null || wait == visitor.waiting()) {
            return;
        }
        visitor.blocked(wait, nanoTime.getAsLong());
        switch (wait) {
            case STREAM -> {
                blockedOnSession.remove(visitor);
                metrics.flowBlocked(FlowScope.STREAM);
            }
            case SESSION -> {
                blockedOnSession.add(visitor);
                metrics.flowBlocked(FlowScope.SESSION);
            }
            case HEADROOM -> {
                blockedOnSession.add(visitor);
                metrics.backpressurePaused(Direction.INBOUND);
            }
        }
    }

    /** What keeps the visitor from sending; {@code null} if nothing credit-related does, as while the session closes. */
    private Wait waitOf(VisitorHandler visitor) {
        if (session.state() == SessionState.CLOSING) {
            return null;
        }
        if (session.streamCredit(visitor.streamId()) == 0) {
            return Wait.STREAM;
        }
        if (session.sessionCredit() == 0) {
            return Wait.SESSION;
        }
        return headroom() == 0 ? Wait.HEADROOM : null;
    }

    private void unblock(VisitorHandler visitor) {
        visitor.unblocked(nanoTime.getAsLong());
    }

    private void leaveLine(VisitorHandler visitor) {
        blockedOnSession.remove(visitor);
        unblock(visitor);
    }

    /** Only when it changes: on epoll each change is a system call. */
    private static void setAutoRead(VisitorHandler visitor, boolean autoRead) {
        if (visitor.channel().config().isAutoRead() != autoRead) {
            visitor.channel().config().setAutoRead(autoRead);
        }
    }

    /** How much more the session channel's outbound buffer takes before it passes its high watermark. */
    private int headroom() {
        return (int) Math.min(Integer.MAX_VALUE, sessionChannel.bytesBeforeUnwritable());
    }

    /** The line of visitors waiting for session credit or headroom, in the order they will be resumed. */
    List<VisitorHandler> blockedOnSession() {
        return List.copyOf(blockedOnSession);
    }
}
