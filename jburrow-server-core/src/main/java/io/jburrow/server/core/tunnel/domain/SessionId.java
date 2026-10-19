package io.jburrow.server.core.tunnel.domain;

import java.util.Objects;
import java.util.UUID;

/** Identifies one control session. Not a secret: it is sent to the client and appears in logs. */
public record SessionId(UUID value) {

    public SessionId {
        Objects.requireNonNull(value, "value");
    }

    public static SessionId random() {
        return new SessionId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
