package io.jburrow.server.core.tunnel.domain;

import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.TunnelKind;
import java.util.Objects;

/** A tunnel a session created: the client's {@code ref} for it, and where the public reaches it. */
public record Tunnel(TunnelId id, String ref, TunnelKind kind, String publicHost, int publicPort) {

    public Tunnel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(publicHost, "publicHost");
        if (publicPort < 1 || publicPort > 65_535) {
            throw new IllegalArgumentException("A public port must be 1 to 65535: " + publicPort);
        }
    }

    /** This tunnel as an entry of the {@code REGISTER} reply. */
    public RegisteredTunnel toRegistered() {
        return RegisteredTunnel.of(ref, id.value(), kind, publicHost, publicPort);
    }
}
