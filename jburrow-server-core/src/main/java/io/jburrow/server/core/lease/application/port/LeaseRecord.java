package io.jburrow.server.core.lease.application.port;

import java.time.Instant;
import java.util.Objects;

/**
 * A lease as {@link LeaseStore} reads and writes it. The store lives in another feature, and features reach each other
 * only through ports, so the domain's {@code Lease} stays inside the engine.
 *
 * @param key the name, or the port in decimal
 */
public record LeaseRecord(Kind kind, String key, String principal, boolean active, Instant expiresAt) {

    public LeaseRecord {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** What the lease keeps. */
    public enum Kind {
        NAME,
        PORT
    }
}
