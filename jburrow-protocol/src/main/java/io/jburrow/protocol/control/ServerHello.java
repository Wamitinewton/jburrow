package io.jburrow.protocol.control;

import java.util.List;
import java.util.UUID;

/**
 * {@code HELLO}, server to client: the session id, the capabilities both peers support, and the session's limits.
 */
public record ServerHello(UUID sessionId, ServerInfo server, List<String> capabilities, SessionLimits limits)
        implements ControlMessage {

    public ServerHello {
        Fields.required(sessionId, "sessionId");
        Fields.required(server, "server");
        capabilities = Fields.capabilities(capabilities, "capabilities");
        Fields.required(limits, "limits");
    }
}
