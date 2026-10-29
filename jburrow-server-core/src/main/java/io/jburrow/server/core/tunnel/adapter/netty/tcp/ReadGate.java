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
 * session's credit or headroom joins the line in {@link #blockedOnSession}, and every visitor in line is resumed, in
 * line order, when stream 0's {@code WINDOW_UPDATE} arrives or the session channel becomes writable again. Neither event
 * touches visitors that aren't waiting.
 *
 * <p>Visitors share the session's credit. When the line is resumed, each visitor in it may take at most an equal share
 * of the credit there is, for its first read: which visitor the transport reads first then doesn't decide who gets the
 * credit, and NIO reads ready channels in no order the gate controls. The share lifts once that read is sent or held,
 * so credit a visitor leaves unused goes to the others in later reads, and every visitor in line is resumed, so none
 * waits while credit sits unused. A visitor keeps its place in line, through any number of resumes and one-byte pauses,
 * until a read of its own is sent; then it leaves, and rejoins at the back if that read spent what it may send.
 *
 * <p>Confined to the session's event loop, so a credit check and the read it allows happen with no gap between them.
 */
final class ReadGate {

    /** Why a visitor isn't read. */
    enum Wait {
        /** Its stream's credit ran out. */
        STREAM,
        /** The session's credit ran out. */
        SESSION,
        /** The session channel's outbound buffer is at its high watermark. */
        HEADROOM
    }

    /** What the gate asks of the relay when carried bytes can go out. */
    interface Output {

        /** Sends {@code bytes} as {@code DATA} on the visitor's stream, spending its credit. Takes over the buffer. */
        void sendCarried(VisitorHandler visitor, ByteBuf bytes);

        /** Sends the {@code CLOSE} the visitor's {@code FIN} was holding back until its carried bytes went out. */
        void sendDeferredClose(VisitorHandler visitor);
    }

    private final Session session;
    private final Channel sessionChannel;
    private final TunnelMetrics metrics;
    private final Output output;
    private final LongSupplier nanoTime;
    /** The line: insertion order is the order visitors joined it, which is the order they are resumed in. */
    private final Set<VisitorHandler> blockedOnSession = new LinkedHashSet<>();

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
     * may send, it stops being read now, and rejoins at the back if it waits for the session.
     */
    void afterSend(VisitorHandler visitor) {
        blockedOnSession.remove(visitor);
        visitor.share(0);
        if (sendable(visitor) == 0) {
            block(visitor);
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
     * Resumes the visitors in line, in line order, each with an equal share of the credit for its first read and
     * keeping its place until a read of its own is sent. Stops once the session can't send again: the visitors it hasn't
     * reached stay paused.
     */
    private void resumeBlockedOnSession() {
        if (blockedOnSession.isEmpty()) {
            return;
        }
        List<VisitorHandler> line = List.copyOf(blockedOnSession);
        int share = Math.max(1, sessionLimit() / line.size());
        for (VisitorHandler visitor : line) {
            if (sessionLimit() == 0) {
                return;
            }
            if (blockedOnSession.contains(visitor)) {
                visitor.share(share);
                reassess(visitor);
            }
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
