package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.Direction;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics.FlowScope;
import io.jburrow.server.core.tunnel.domain.Session;
import io.jburrow.server.core.tunnel.domain.SessionState;
import io.netty.channel.Channel;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Decides when each exchange of one session may send request body, {@code docs/phase-3-http-tunnels/ARCHITECTURE.md}
 * §Visitor to client: demand reads. An exchange asks for a grant before every read of the visitor, and before sending
 * body bytes it already holds; a grant is a reservation of up to {@link #DEMAND} bytes of its stream's credit and the
 * session's, no more than the session channel's headroom.
 *
 * <p>An exchange granted nothing waits. One short of its stream's credit is resumed by that stream's
 * {@code WINDOW_UPDATE}. One short of the session's credit or headroom joins the line, and is resumed in line order by
 * stream 0's {@code WINDOW_UPDATE} or the session channel becoming writable, as phase 2's read gate resumes visitors.
 * Each demand is phase 2's share floor, so a resume is simply the next demand, and as many exchanges are resumed as the
 * credit covers demands.
 *
 * <p>Turns: an exchange whose read is done while others wait in line rejoins at the back, so one exchange with a fast
 * visitor can't take every grant the session's credit makes.
 *
 * <p>Confined to the session's event loop, so a credit check and the reservation it allows happen with no gap.
 */
final class ExchangeGate {

    /** The most one grant reserves: phase 2's share floor, ADR-0010. */
    static final int DEMAND = 16_384;

    /** Why an exchange isn't granted anything. */
    enum Wait {
        /** Its stream's credit ran out. */
        STREAM,
        /** The session's credit ran out. */
        SESSION,
        /** The session channel's outbound buffer is at its high watermark. */
        HEADROOM,
        /** It had its turn, and others in line go first. */
        TURN
    }

    /** An exchange as the gate sees it. */
    interface Reader {

        int streamId();

        /** It may ask for a grant again. Called on the session's event loop, never from inside {@link #grant}. */
        void resume();
    }

    private final Session session;
    private final Channel sessionChannel;
    private final TunnelMetrics metrics;
    private final Map<Reader, Wait> waiting = new HashMap<>();
    /** Insertion order is the order readers joined, which is the order they are resumed in. */
    private final Set<Reader> line = new LinkedHashSet<>();

    ExchangeGate(Session session, Channel sessionChannel, TunnelMetrics metrics) {
        this.session = Objects.requireNonNull(session, "session");
        this.sessionChannel = Objects.requireNonNull(sessionChannel, "sessionChannel");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Reserves up to {@link #DEMAND} bytes for {@code reader}'s next send. The caller spends the grant with
     * {@link Session#onReservedDataSent} and gives back what it doesn't use with {@link Session#refund}.
     *
     * @return the bytes reserved; 0 if the reader must wait, which it does until {@link Reader#resume}
     */
    int grant(Reader reader) {
        int max = session.state() == SessionState.CLOSING ? 0 : Math.min(DEMAND, headroom());
        int granted = max == 0 ? 0 : session.reserve(reader.streamId(), max);
        if (granted == 0) {
            block(reader);
        } else {
            leave(reader);
        }
        return granted;
    }

    /**
     * {@code reader} has used a grant and wants another. If others wait in line, it takes its place at the back and the
     * line is resumed; otherwise it may ask again at once.
     *
     * @return whether the reader may call {@link #grant} now
     */
    boolean turnTaken(Reader reader) {
        if (line.isEmpty()) {
            return true;
        }
        waiting.put(reader, Wait.TURN);
        line.add(reader);
        resumeLine();
        return false;
    }

    /** A {@code WINDOW_UPDATE} on the reader's stream: resumed if that is what it waited for. */
    void onStreamCredit(Reader reader) {
        if (waiting.get(reader) == Wait.STREAM) {
            waiting.remove(reader);
            reader.resume();
        }
    }

    /** A {@code WINDOW_UPDATE} on stream 0. */
    void onSessionCredit() {
        resumeLine();
    }

    void onSessionWritable(boolean writable) {
        if (writable) {
            resumeLine();
        }
    }

    /** The reader's exchange ended: forget it. */
    void onRemoved(Reader reader) {
        leave(reader);
    }

    /** What the reader waits for, or {@code null} if it isn't waiting. */
    Wait waiting(Reader reader) {
        return waiting.get(reader);
    }

    /** The readers waiting for the session's credit, headroom or their turn, in the order they will be resumed. */
    List<Reader> line() {
        return List.copyOf(line);
    }

    /**
     * Resumes readers in line, in line order, while the session has credit and headroom to grant. Each resumed reader
     * asks for its grant at once, so the line stops where the credit does.
     */
    private void resumeLine() {
        for (Reader reader : List.copyOf(line)) {
            if (sessionLimit() == 0) {
                return;
            }
            if (line.contains(reader)) {
                reader.resume();
            }
        }
    }

    /**
     * Records what the reader waits for, counting each new wait once. One waiting for the session joins the back of the
     * line, unless it already has a place.
     */
    private void block(Reader reader) {
        Wait wait = waitOf(reader);
        Wait previous = waiting.put(reader, wait);
        if (wait == Wait.STREAM) {
            line.remove(reader);
        } else {
            line.add(reader);
        }
        if (wait == previous) {
            return;
        }
        switch (wait) {
            case STREAM -> metrics.flowBlocked(FlowScope.STREAM);
            case SESSION -> metrics.flowBlocked(FlowScope.SESSION);
            case HEADROOM -> metrics.backpressurePaused(Direction.INBOUND);
            case TURN -> {}
        }
    }

    /**
     * In phase 2's order: a reader without stream credit waits for its stream whatever the session has. A closing
     * session grants nothing; its exchanges are about to end, so they wait for the session.
     */
    private Wait waitOf(Reader reader) {
        if (session.state() == SessionState.CLOSING) {
            return Wait.SESSION;
        }
        if (session.streamCredit(reader.streamId()) == 0) {
            return Wait.STREAM;
        }
        return session.sessionCredit() == 0 ? Wait.SESSION : Wait.HEADROOM;
    }

    private void leave(Reader reader) {
        waiting.remove(reader);
        line.remove(reader);
    }

    /** What every exchange together may still be granted. */
    private int sessionLimit() {
        return session.state() == SessionState.CLOSING ? 0 : Math.min(session.sessionCredit(), headroom());
    }

    private int headroom() {
        return (int) Math.min(Integer.MAX_VALUE, sessionChannel.bytesBeforeUnwritable());
    }
}
