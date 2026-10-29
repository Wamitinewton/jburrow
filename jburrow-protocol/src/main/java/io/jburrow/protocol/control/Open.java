package io.jburrow.protocol.control;

import java.util.UUID;

/**
 * {@code OPEN}, server to client: a new stream for a tunnel, and the visitor it carries. An HTTP stream carries one
 * request, whose head is {@code request}; a TCP stream has none, and {@code request} is null.
 */
public record Open(UUID tunnelId, TunnelKind kind, RemoteAddress remote, HttpRequestHead request)
        implements ControlMessage {

    public Open {
        Fields.required(tunnelId, "tunnelId");
        Fields.required(kind, "kind");
        Fields.required(remote, "remote");
        if (kind == TunnelKind.HTTP) {
            Fields.required(request, "request");
        } else if (request != null) {
            throw Fields.invalid("request", "is only allowed with kind http");
        }
    }

    /** A stream without a request head, for a TCP tunnel. */
    public Open(UUID tunnelId, TunnelKind kind, RemoteAddress remote) {
        this(tunnelId, kind, remote, null);
    }
}
