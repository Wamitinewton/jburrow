package io.jburrow.server.core.tunnel.application.port;

import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.server.core.portpool.application.port.PortPoolSnapshot;
import io.jburrow.server.core.tunnel.domain.StreamAdmission;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What the engine reports, one method per metric of {@code docs/phase-1-tcp-tunnel/ARCHITECTURE.md} §Observability.
 * Arguments are enums and numbers only, so no label can carry a session id, tunnel id, port, address or principal.
 * Called on event loops, so an implementation never blocks.
 */
public interface TunnelMetrics {

    /** The way bytes travel through a tunnel. */
    enum Direction {
        /** From a visitor towards the client. */
        INBOUND,
        /** From the client towards a visitor. */
        OUTBOUND
    }

    /** Which side of a session's connection a frame crossed. */
    enum FrameDirection {
        RECEIVED,
        SENT
    }

    /** Who raised an error: this server, or its peer. */
    enum Side {
        LOCAL,
        REMOTE
    }

    /** Which credit a sender ran out of. */
    enum FlowScope {
        /** Its own stream's: the receiver of that stream is slower than its sender. */
        STREAM,
        /** The session's, shared by every stream: the client is slower than its visitors together. */
        SESSION
    }

    /** Why a session ended without a session error. */
    enum SessionEnd {
        CLIENT_CLOSED,
        CONNECTION_LOST
    }

    /** A connection upgraded and its session began: {@code jburrow_sessions_active} up. */
    void sessionStarted();

    /** A session ended with a session error: {@code jburrow_sessions_active} down, {@code _closed_total} up. */
    void sessionEnded(ErrorCode reason);

    /** A session ended without a session error. */
    void sessionEnded(SessionEnd reason);

    /** {@code jburrow_session_handshakes_total{result="established"}}. */
    void handshakeEstablished();

    /** {@code jburrow_session_handshakes_total{result=<code>}}. */
    void handshakeFailed(ErrorCode code);

    /** {@code jburrow_register_duration_seconds{result="established"}}. */
    void registerSucceeded(long durationNanos);

    /** {@code jburrow_register_duration_seconds{result=<code>}}. */
    void registerFailed(long durationNanos, ErrorCode code);

    /** {@code jburrow_streams_opened_total} and {@code jburrow_streams_active} up. */
    void streamOpened(TunnelKind kind);

    /** {@code jburrow_streams_active} down. */
    void streamClosed(TunnelKind kind);

    void streamReset(ErrorCode code, Side side);

    void visitorRefused(StreamAdmission.Refusal reason);

    void tunnelBytes(TunnelKind kind, Direction direction, long bytes);

    void frame(FrameType type, FrameDirection direction);

    void protocolError(ErrorCode code, Side side);

    /** Reading one side stopped because the other couldn't keep up. */
    void backpressurePaused(Direction direction);

    /** {@code jburrow_flow_blocked_total}: a visitor stopped being read because {@code scope}'s credit ran out. */
    void flowBlocked(FlowScope scope);

    /** Binds {@code jburrow_tcp_ports} to the pool. */
    void bindPortPool(Supplier<PortPoolSnapshot> snapshot);

    /** Binds {@code jburrow_netty_direct_memory_bytes}. */
    void bindDirectMemory(LongSupplier usedBytes);

    /** Metrics that go nowhere. */
    static TunnelMetrics noop() {
        return NoopTunnelMetrics.INSTANCE;
    }
}
