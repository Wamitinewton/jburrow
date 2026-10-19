package io.jburrow.server.core.tunnel.application;

import io.jburrow.server.core.tunnel.domain.Principal;
import java.util.List;
import java.util.Objects;

/** An accepted {@code REGISTER}: who registered, and a planned tunnel per request entry, in request order. */
public record Registration(Principal principal, List<PlannedTunnel> tunnels) {

    public Registration {
        Objects.requireNonNull(principal, "principal");
        tunnels = List.copyOf(tunnels);
    }
}
