package io.jburrow.protocol.control;

import java.util.List;

/** {@code HELLO}, client to server: who the client is and the capabilities it supports. */
public record Hello(ClientInfo client, List<String> capabilities) implements ControlMessage {

    public Hello {
        Fields.required(client, "client");
        capabilities = Fields.capabilities(capabilities, "capabilities");
    }
}
