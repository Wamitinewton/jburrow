package io.jburrow.protocol.control;

import java.util.List;

/** {@code REGISTER}, server to client: one entry per requested tunnel, in request order. */
public record Registered(List<RegisteredTunnel> tunnels) implements ControlMessage {

    public Registered {
        tunnels = Fields.list(tunnels, 1, Fields.MAX_TUNNELS, "tunnels");
        Fields.requireUnique(tunnels, RegisteredTunnel::ref, "tunnels", ".ref");
    }
}
