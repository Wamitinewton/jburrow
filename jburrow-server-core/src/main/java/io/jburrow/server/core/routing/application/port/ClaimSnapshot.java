package io.jburrow.server.core.routing.application.port;

import io.jburrow.protocol.control.SubdomainName;
import java.time.Instant;
import java.util.Objects;

/**
 * A name claimed for {@code principal}: by a live tunnel while {@code heldUntil} is {@code null}, else held for the
 * principal until then.
 */
public record ClaimSnapshot<P>(SubdomainName name, P principal, Instant heldUntil) {

    public ClaimSnapshot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(principal, "principal");
    }

    public boolean active() {
        return heldUntil == null;
    }
}
