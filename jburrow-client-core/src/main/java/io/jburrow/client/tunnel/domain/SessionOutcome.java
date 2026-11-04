package io.jburrow.client.tunnel.domain;

import io.jburrow.protocol.wire.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether the client tries again after a session ended or an attempt failed, and on which schedule:
 * ARCHITECTURE.md §When to give up, phase 4. The client tries again unless the server said retrying can't help.
 *
 * <p>A first session, one that has never been established, stops at once, except on {@code SERVER_SHUTTING_DOWN},
 * {@code SERVER_AT_CAPACITY} and an upgrade refused with 502, 503 or 504: a deploy in progress, retried on the
 * {@code GOAWAY} cadence for at most 60 seconds.
 */
public sealed interface SessionOutcome {

    /** Try again, once {@code after} has passed. */
    record Reconnect(Wait after) implements SessionOutcome {

        public Reconnect {
            Objects.requireNonNull(after, "after");
        }
    }

    /**
     * The server refused the name or port of tunnel {@code ref} as taken, and it chose that one itself for an earlier
     * session: ask once more, without it, at once. The tunnel then gets a new address.
     */
    record RetryWithoutPinned(String ref) implements SessionOutcome {

        public RetryWithoutPinned {
            Objects.requireNonNull(ref, "ref");
        }
    }

    /** Stop for good, with {@code reason} as the last word. */
    record Stop(TunnelClosed reason) implements SessionOutcome {

        public Stop {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Which row of the schedule the next attempt waits on. */
    sealed interface Wait {

        /** A session ended without a {@code GOAWAY}. */
        record AfterLoss() implements Wait {}

        /** A session ended after a {@code GOAWAY} asking for at least {@code reconnectAfter}. */
        record AfterGoAway(Duration reconnectAfter) implements Wait {}

        /** An attempt was refused with {@code Retry-After}. */
        record AfterRetryAfter(Duration retryAfter) implements Wait {}

        /** An attempt failed otherwise: the run carries on. */
        record Continue() implements Wait {}

        /** The first session failed as a deploy would make it, and asked for at least {@code atLeast}. */
        record StartingUp(Duration atLeast) implements Wait {}
    }

    /**
     * Decides what follows {@code end}.
     *
     * @param firstSession whether no session of this client has been established yet
     * @param requested the tunnels as the user asked for them
     * @param resumption what the attempt that ended asked for again
     */
    static SessionOutcome of(SessionEnd end, boolean firstSession, List<TunnelSpec> requested, Resumption resumption) {
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(resumption, "resumption");
        TunnelClosed closed = end.closed();
        Optional<ErrorCode> code = closed.code();
        boolean retryable =
                switch (closed.reason()) {
                    case USER_CLOSED -> false;
                    case CONNECTION_LOST, HEARTBEAT_TIMEOUT ->
                        code.map(SessionOutcome::retryable).orElse(true);
                    case SERVER_ERROR, LOCAL_ERROR ->
                        code.map(SessionOutcome::retryable).orElse(false);
                };
        if (!retryable) {
            if (code.isPresent() && takenByAnother(code.get())) {
                Optional<TunnelSpec> refused = refused(end, requested);
                if (refused.isPresent() && resumption.pin(refused.get()) == Resumption.Pin.SERVER_CHOSEN) {
                    return new RetryWithoutPinned(refused.get().ref());
                }
            }
            return new Stop(closed);
        }
        if (firstSession) {
            return deployInProgress(end)
                    ? new Reconnect(new Wait.StartingUp(end.retryAfter().orElse(Duration.ZERO)))
                    : new Stop(closed);
        }
        if (end.established()) {
            return new Reconnect(end.goAway().<Wait>map(Wait.AfterGoAway::new).orElseGet(Wait.AfterLoss::new));
        }
        return new Reconnect(
                end.retryAfter().<Wait>map(Wait.AfterRetryAfter::new).orElseGet(Wait.Continue::new));
    }

    /** Whether a session or an attempt that ended with {@code code} is worth trying again. */
    private static boolean retryable(ErrorCode code) {
        return switch (code) {
            case SERVER_SHUTTING_DOWN, SERVER_AT_CAPACITY, INTERNAL_ERROR, HANDSHAKE_TIMEOUT, HEARTBEAT_TIMEOUT -> true;
            case AUTHTOKEN_INVALID,
                    VALIDATION_FAILED,
                    TUNNEL_LIMIT_EXCEEDED,
                    SESSION_LIMIT_EXCEEDED,
                    PORT_UNAVAILABLE,
                    PORT_POOL_EXHAUSTED,
                    SUBDOMAIN_UNAVAILABLE,
                    SUBDOMAIN_FORBIDDEN,
                    UNSUPPORTED_PROTOCOL_VERSION,
                    PROTOCOL_ERROR,
                    FRAME_SIZE_ERROR,
                    FLOW_CONTROL_ERROR,
                    PING_RATE_EXCEEDED,
                    FORBIDDEN_ORIGIN,
                    NOT_FOUND,
                    SESSION_REPLACED -> false;
            // Stream codes never end a session; one here is a bug, and retrying won't fix it.
            case PEER_RESET, LOCAL_SERVICE_UNREACHABLE, LOCAL_SERVICE_ERROR, STREAM_CLOSED, CANCELLED -> false;
        };
    }

    /**
     * Whether the first session failed as a deploy makes it fail: the server is stopping or full, or a gateway in front
     * of it answered 502, 503 or 504.
     */
    private static boolean deployInProgress(SessionEnd end) {
        Optional<ErrorCode> code = end.closed().code();
        if (code.isPresent()) {
            return code.get() == ErrorCode.SERVER_SHUTTING_DOWN || code.get() == ErrorCode.SERVER_AT_CAPACITY;
        }
        int status = end.upgradeStatus().orElse(0);
        return status == 502 || status == 503 || status == 504;
    }

    private static boolean takenByAnother(ErrorCode code) {
        return code == ErrorCode.PORT_UNAVAILABLE || code == ErrorCode.SUBDOMAIN_UNAVAILABLE;
    }

    /** The tunnel the refusal is about: the one it named, or the only one. */
    private static Optional<TunnelSpec> refused(SessionEnd end, List<TunnelSpec> requested) {
        if (end.refusedRef().isPresent()) {
            String ref = end.refusedRef().get();
            return requested.stream().filter(spec -> spec.ref().equals(ref)).findFirst();
        }
        return requested.size() == 1 ? Optional.of(requested.getFirst()) : Optional.empty();
    }
}
