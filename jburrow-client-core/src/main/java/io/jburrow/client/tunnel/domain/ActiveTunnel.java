package io.jburrow.client.tunnel.domain;

import io.jburrow.protocol.control.RegisteredTunnel;
import java.util.Objects;
import java.util.UUID;

/** A tunnel the server created for this session, and the local target its streams are forwarded to. */
public record ActiveTunnel(RegisteredTunnel registered, LocalTarget target) {

    public ActiveTunnel {
        Objects.requireNonNull(registered, "registered");
        Objects.requireNonNull(target, "target");
    }

    public UUID id() {
        return registered.tunnelId();
    }

    public String ref() {
        return registered.ref();
    }
}
