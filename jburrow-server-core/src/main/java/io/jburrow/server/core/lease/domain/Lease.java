package io.jburrow.server.core.lease.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One claim of one server process as it was written down: a name or a port, who it is for, whether a live tunnel had
 * it, and when it stops counting.
 *
 * @param key the name, or the port in decimal
 * @param principal the principal, as the composition root turns it into a string
 */
public record Lease(LeaseKind kind, String key, String principal, boolean active, Instant expiresAt) {

    public static final int MAX_PORT = 65_535;

    public Lease {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (key.isBlank()) {
            throw new IllegalArgumentException("A lease key must not be blank");
        }
        if (principal.isBlank()) {
            throw new IllegalArgumentException("A lease principal must not be blank");
        }
    }

    public static Lease name(String name, String principal, boolean active, Instant expiresAt) {
        return new Lease(LeaseKind.NAME, name, principal, active, expiresAt);
    }

    public static Lease port(int port, String principal, boolean active, Instant expiresAt) {
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalArgumentException("A leased port must be between 1 and " + MAX_PORT + ": " + port);
        }
        return new Lease(LeaseKind.PORT, Integer.toString(port), principal, active, expiresAt);
    }

    public boolean expiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
