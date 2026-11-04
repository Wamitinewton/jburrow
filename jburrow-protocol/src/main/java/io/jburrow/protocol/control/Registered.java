package io.jburrow.protocol.control;

import java.util.List;

/**
 * {@code REGISTER}, server to client: one entry per requested tunnel, in request order, and with {@code resume} the
 * session's resume token. {@code resumeToken} is optional and may be null.
 */
public record Registered(List<RegisteredTunnel> tunnels, ResumeToken resumeToken) implements ControlMessage {

    public Registered {
        tunnels = Fields.list(tunnels, 1, Fields.MAX_TUNNELS, "tunnels");
        Fields.requireUnique(tunnels, RegisteredTunnel::ref, "tunnels", ".ref");
    }

    /** A reply without a resume token. */
    public Registered(List<RegisteredTunnel> tunnels) {
        this(tunnels, null);
    }
}
