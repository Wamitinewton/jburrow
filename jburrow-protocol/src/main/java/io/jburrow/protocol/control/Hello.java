package io.jburrow.protocol.control;

import java.util.List;

/**
 * {@code HELLO}, client to server: who the client is, the capabilities it supports, and its receive windows.
 */
public record Hello(ClientInfo client, List<String> capabilities, Windows windows) implements ControlMessage {

    public Hello {
        Fields.required(client, "client");
        capabilities = Fields.capabilities(capabilities, "capabilities");
        Fields.required(windows, "windows");
    }
}
