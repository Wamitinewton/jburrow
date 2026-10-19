package io.jburrow.server.core.tunnel.domain;

/** The server's view of a control session, {@code docs/protocol.md} §8.1. */
public enum SessionState {
    /** Connected; the client's {@code HELLO} hasn't arrived. */
    AWAITING_HELLO,
    /** {@code HELLO}s exchanged; the client's {@code REGISTER} hasn't arrived. */
    AWAITING_REGISTER,
    /** The server is checking the {@code REGISTER} and creating the tunnels. */
    REGISTERING,
    /** The {@code REGISTER} reply is sent. Streams may be opened. */
    ESTABLISHED,
    /** The session is ending; nothing new starts. */
    CLOSING
}
