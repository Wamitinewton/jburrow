package io.jburrow.server.core.tunnel.domain;

import java.util.Objects;
import java.util.UUID;

/** Identifies one tunnel. The client receives it in the {@code REGISTER} reply and finds it again in each {@code OPEN}. */
public record TunnelId(UUID value) {

    public TunnelId {
        Objects.requireNonNull(value, "value");
    }

    public static TunnelId random() {
        return new TunnelId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
