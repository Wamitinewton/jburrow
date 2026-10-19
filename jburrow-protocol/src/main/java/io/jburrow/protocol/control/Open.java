package io.jburrow.protocol.control;

import java.util.UUID;

/** {@code OPEN}, server to client: a new stream for a tunnel, and the visitor it carries. */
public record Open(UUID tunnelId, TunnelKind kind, RemoteAddress remote) implements ControlMessage {

    public Open {
        Fields.required(tunnelId, "tunnelId");
        Fields.required(kind, "kind");
        Fields.required(remote, "remote");
    }
}
