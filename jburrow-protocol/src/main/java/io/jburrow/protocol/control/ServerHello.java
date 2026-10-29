package io.jburrow.protocol.control;

import java.util.List;
import java.util.UUID;

/**
 * {@code HELLO}, server to client: the session id, the capabilities both peers support, the session's limits, and the
 * server's receive windows.
 */
public record ServerHello(
        UUID sessionId, ServerInfo server, List<String> capabilities, SessionLimits limits, Windows windows)
        implements ControlMessage {

    public ServerHello {
        Fields.required(sessionId, "sessionId");
        Fields.required(server, "server");
        capabilities = Fields.capabilities(capabilities, "capabilities");
        Fields.required(limits, "limits");
        Fields.required(windows, "windows");
    }
}
