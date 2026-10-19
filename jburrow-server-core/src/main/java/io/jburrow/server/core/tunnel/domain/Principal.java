package io.jburrow.server.core.tunnel.domain;

import java.util.Objects;

/** Who an authtoken belongs to. Session limits, logs and metrics count per principal. */
public record Principal(String name) {

    public static final int MAX_NAME_LENGTH = 64;

    public Principal {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "A principal name must be non-blank and at most " + MAX_NAME_LENGTH + " characters");
        }
    }
}
