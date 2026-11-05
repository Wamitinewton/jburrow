package io.jburrow.server.core.tunnel.application.port;

import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.server.core.portpool.application.port.PortPoolSnapshot;
import io.jburrow.server.core.tunnel.domain.StreamAdmission;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What the engine reports, one method per metric of the §Observability sections of
 * {@code docs/phase-1-tcp-tunnel/ARCHITECTURE.md} and the phases after it.
 * Arguments are enums, numbers and codes from a fixed set only, so no label can carry a session id, tunnel id, port,
 * address or principal.
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

    /** Who answered an HTTP request. */
    enum ResponseOrigin {
        /** The local service, through the tunnel. */
        TUNNEL,
        /** The edge itself, with an error page. */
        EDGE
    }

    /** How a registration's claim on a subdomain ended. */
    enum ClaimOutcome {
        /** The server chose the name. */
        RANDOM,
        /** The client's requested name was claimed. */
        REQUESTED,
        /** The requested name was held for the same principal, which took it back. */
        RECLAIMED,
        /** The requested name is in use, or held for someone else. */
        UNAVAILABLE,
        /** The requested name is reserved or blocked. */
        FORBIDDEN
    }

    /** How a registration's reservation of a tunnel port ended. */
    enum PortClaimOutcome {
        /** The server chose the port. */
        RANDOM,
        /** The client's requested port was reserved. */
        REQUESTED,
        /** The requested port was held for the same principal, which took it back. */
        RECLAIMED,
        /** The requested port is outside the range, reserved, or held for someone else. */
        UNAVAILABLE
    }

    /** What a {@code REGISTER} carrying a resume token found (step 2a of {@code docs/protocol.md} §8.4). */
    enum ResumeOutcome {
        /** The token named a live session of the same owner, which was ended. */
        REPLACED,
        /** It named none: unknown, already used, ended, or another owner's. */
        NONE
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

    /** {@code jburrow_http_requests_total}: a response head with {@code status} went to a visitor. */
    default void httpResponse(int status, ResponseOrigin origin) {}

    /**
     * {@code jburrow_http_edge_errors_total}: the edge answered with the error page of {@code code}, one of the edge's
     * fixed set of page codes.
     */
    default void edgeErrorPage(String code) {}

    /** {@code jburrow_http_response_head_seconds}: from {@code OPEN} to the final response head. */
    default void responseHead(long durationNanos) {}

    /** {@code jburrow_flow_parked_total}: a stalled response's bytes were parked. */
    default void streamParked() {}

    /** {@code jburrow_flow_park_refused_total}: a stalled response didn't fit the parking budget. */
    default void parkRefused() {}

    /** {@code jburrow_subdomain_claims_total}: registration claimed a name, or was refused the one it asked for. */
    default void subdomainClaimed(ClaimOutcome outcome) {}

    /** {@code jburrow_port_claims_total}: registration reserved a port, or was refused the one it asked for. */
    default void portClaimed(PortClaimOutcome outcome) {}

    /** {@code jburrow_resume_total}: a registration presented a resume token. Called on a virtual thread. */
    default void resumed(ResumeOutcome outcome) {}

    /** {@code jburrow_goaway_sent_total}: a draining session was sent {@code GOAWAY}. */
    default void goAwaySent() {}

    /** {@code jburrow_drain_streams_cut_total}: {@code streams} were still open when the drain ended. */
    default void drainStreamsCut(int streams) {}

    /** Binds {@code jburrow_flow_parked_bytes} to the bytes parked across the server. */
    default void bindParkedBytes(LongSupplier parkedBytes) {}

    /** Binds {@code jburrow_tcp_ports}, and {@code jburrow_holds{kind="port"}} to its held ports, to the pool. */
    void bindPortPool(Supplier<PortPoolSnapshot> snapshot);

    /** Binds {@code jburrow_holds{kind="name"}} to the names on hold now. */
    default void bindHeldNames(IntSupplier heldNames) {}

    /** Binds {@code jburrow_netty_direct_memory_bytes}. */
    void bindDirectMemory(LongSupplier usedBytes);

    /** Metrics that go nowhere. */
    static TunnelMetrics noop() {
        return NoopTunnelMetrics.INSTANCE;
    }
}
