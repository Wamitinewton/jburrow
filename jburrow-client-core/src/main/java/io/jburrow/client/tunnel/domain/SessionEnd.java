package io.jburrow.client.tunnel.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * How a session ended, or how an attempt to start one failed: what {@link SessionOutcome} decides on.
 *
 * <p>A failed attempt reads as the end it would have been: no code for a connection that failed or timed out, the
 * refusal's code for a refused upgrade, the {@code ERROR}'s code for a refused registration. An attempt this client
 * ended because the server lacks a capability the tunnels need is a {@link TunnelClosed.Reason#LOCAL_ERROR} without a
 * code.
 *
 * @param closed the reason, as the events report it
 * @param established whether the session was established, rather than an attempt that failed
 * @param goAway the {@code reconnectAfterMs} of a {@code GOAWAY} the session received before it ended
 * @param upgradeStatus the HTTP status of a refused upgrade
 * @param retryAfter a refused upgrade's {@code Retry-After}
 * @param refusedRef the tunnel the server's {@code ERROR} named, if it named one
 */
public record SessionEnd(
        TunnelClosed closed,
        boolean established,
        Optional<Duration> goAway,
        OptionalInt upgradeStatus,
        Optional<Duration> retryAfter,
        Optional<String> refusedRef) {

    public SessionEnd {
        Objects.requireNonNull(closed, "closed");
        Objects.requireNonNull(goAway, "goAway");
        Objects.requireNonNull(upgradeStatus, "upgradeStatus");
        Objects.requireNonNull(retryAfter, "retryAfter");
        Objects.requireNonNull(refusedRef, "refusedRef");
    }

    /** An established session ended, after a {@code GOAWAY} asking for {@code goAway} if it had one. */
    public static SessionEnd ofSession(TunnelClosed closed, Optional<Duration> goAway) {
        return new SessionEnd(closed, true, goAway, OptionalInt.empty(), Optional.empty(), Optional.empty());
    }

    /** An attempt failed before its session was established. */
    public static SessionEnd ofAttempt(
            TunnelClosed closed,
            OptionalInt upgradeStatus,
            Optional<Duration> retryAfter,
            Optional<String> refusedRef) {
        return new SessionEnd(closed, false, Optional.empty(), upgradeStatus, retryAfter, refusedRef);
    }
}
