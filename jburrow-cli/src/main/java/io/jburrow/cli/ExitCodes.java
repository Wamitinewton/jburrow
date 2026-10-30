package io.jburrow.cli;

import io.jburrow.client.TunnelStartException;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.wire.ErrorCode;
import picocli.CommandLine.IExitCodeExceptionMapper;
import picocli.CommandLine.ParameterException;

/**
 * The process exit codes, as phase 1's ARCHITECTURE.md §The CLI lists them, with {@code FLOW_CONTROL_ERROR} added to
 * 6 by phase 2's §Failure modes and the subdomain refusals added to 5 by phase 3's §The CLI. A script decides on these, so a code's meaning never changes once released.
 */
final class ExitCodes {

    static final int USER_CLOSED = 0;
    static final int INTERNAL_ERROR = 1;
    static final int USAGE = 2;
    static final int AUTHENTICATION_FAILED = 3;
    static final int CONNECTION_FAILED = 4;
    static final int REGISTRATION_REFUSED = 5;
    static final int PROTOCOL_MISMATCH = 6;

    /** A usage error from parsing or from a command's own checks is 2; anything else thrown is a bug. */
    static final IExitCodeExceptionMapper EXCEPTIONS =
            exception -> exception instanceof ParameterException ? USAGE : INTERNAL_ERROR;

    private ExitCodes() {}

    static int of(TunnelStartException failure) {
        if (failure.code().isPresent()) {
            return of(failure.code().get());
        }
        return switch (failure.kind()) {
            case CONNECT_FAILED, TLS_FAILED, TIMEOUT, UPGRADE_REFUSED, SERVER_ERROR -> CONNECTION_FAILED;
            case UNSUPPORTED_BY_SERVER -> PROTOCOL_MISMATCH;
            case LOCAL_ERROR -> INTERNAL_ERROR;
        };
    }

    static int of(TunnelClosed closed) {
        return switch (closed.reason()) {
            case USER_CLOSED -> USER_CLOSED;
            case CONNECTION_LOST, HEARTBEAT_TIMEOUT -> CONNECTION_FAILED;
            case SERVER_ERROR, LOCAL_ERROR -> closed.code().map(ExitCodes::of).orElse(INTERNAL_ERROR);
        };
    }

    /** Exhaustive on purpose: a new error code doesn't compile until it has an exit code. */
    static int of(ErrorCode code) {
        return switch (code) {
            case AUTHTOKEN_INVALID -> AUTHENTICATION_FAILED;
            case NOT_FOUND,
                    SERVER_AT_CAPACITY,
                    SERVER_SHUTTING_DOWN,
                    HANDSHAKE_TIMEOUT,
                    HEARTBEAT_TIMEOUT,
                    INTERNAL_ERROR -> CONNECTION_FAILED;
            case VALIDATION_FAILED,
                    TUNNEL_LIMIT_EXCEEDED,
                    SESSION_LIMIT_EXCEEDED,
                    PORT_UNAVAILABLE,
                    PORT_POOL_EXHAUSTED,
                    SUBDOMAIN_UNAVAILABLE,
                    SUBDOMAIN_FORBIDDEN -> REGISTRATION_REFUSED;
            case UNSUPPORTED_PROTOCOL_VERSION,
                    PROTOCOL_ERROR,
                    FRAME_SIZE_ERROR,
                    FLOW_CONTROL_ERROR,
                    FORBIDDEN_ORIGIN,
                    PING_RATE_EXCEEDED -> PROTOCOL_MISMATCH;
            // Stream codes never end a session; seeing one here is a bug.
            case PEER_RESET, LOCAL_SERVICE_UNREACHABLE, LOCAL_SERVICE_ERROR, STREAM_CLOSED, CANCELLED ->
                ExitCodes.INTERNAL_ERROR;
        };
    }
}
