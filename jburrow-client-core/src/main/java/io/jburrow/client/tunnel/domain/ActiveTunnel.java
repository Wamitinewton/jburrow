package io.jburrow.client.tunnel.domain;

import io.jburrow.protocol.control.RegisteredTunnel;
import java.util.Objects;
import java.util.UUID;

/** A tunnel the server created for this session, and the spec it answers, whose local target its streams reach. */
public record ActiveTunnel(RegisteredTunnel registered, TunnelSpec spec) {

    public ActiveTunnel {
        Objects.requireNonNull(registered, "registered");
        Objects.requireNonNull(spec, "spec");
    }

    public UUID id() {
        return registered.tunnelId();
    }

    public String ref() {
        return registered.ref();
    }

    public LocalTarget target() {
        return spec.target();
    }
}
