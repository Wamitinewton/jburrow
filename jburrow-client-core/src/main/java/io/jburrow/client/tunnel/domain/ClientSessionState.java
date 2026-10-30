package io.jburrow.client.tunnel.domain;

/** Where a session is, seen from the client: {@code docs/protocol.md} §8.1 with "sent" and "received" swapped. */
public enum ClientSessionState {
    /** Connecting and upgrading; no frame has been exchanged. */
    CONNECTING,
    /**
     * {@code HELLO} is sent, and {@code REGISTER} too unless it needs a capability the server's {@code HELLO} must list
     * first; the server's {@code HELLO} hasn't arrived.
     */
    AWAITING_HELLO,
    /** The server's {@code HELLO} arrived; its {@code REGISTER} reply hasn't. */
    AWAITING_REGISTERED,
    /** The {@code REGISTER} reply arrived. Streams may be opened. */
    ESTABLISHED,
    /** The session is ending; nothing new starts. */
    CLOSING
}
