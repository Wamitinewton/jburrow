package io.jburrow.server.core.portpool.application.port;

import java.time.Instant;
import java.util.Objects;

/**
 * A port claimed for {@code principal}: by a live tunnel while {@code heldUntil} is {@code null}, else held for the
 * principal until then.
 */
public record PortClaimSnapshot<P>(int port, P principal, Instant heldUntil) {

    public PortClaimSnapshot {
        Objects.requireNonNull(principal, "principal");
    }

    public boolean active() {
        return heldUntil == null;
    }
}
