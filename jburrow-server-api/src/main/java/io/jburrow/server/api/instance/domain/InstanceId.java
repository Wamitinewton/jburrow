package io.jburrow.server.api.instance.domain;

import java.util.Objects;
import java.util.UUID;

/** Identifies one start of one server process. A new one is drawn on every start. */
public record InstanceId(UUID value) {

    public InstanceId {
        Objects.requireNonNull(value, "value");
    }

    public static InstanceId random() {
        return new InstanceId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
