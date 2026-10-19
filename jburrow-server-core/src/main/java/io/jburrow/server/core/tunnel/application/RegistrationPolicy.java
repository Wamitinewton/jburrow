package io.jburrow.server.core.tunnel.application;

import java.util.Objects;

/** The server's terms for a {@code REGISTER}: how many tunnels a session may have, and the host they are public on. */
public record RegistrationPolicy(int maxTunnels, String publicHost) {

    public RegistrationPolicy {
        if (maxTunnels < 1) {
            throw new IllegalArgumentException("maxTunnels must be positive: " + maxTunnels);
        }
        Objects.requireNonNull(publicHost, "publicHost");
        if (publicHost.isBlank()) {
            throw new IllegalArgumentException("publicHost must not be blank");
        }
    }
}
