package io.jburrow.protocol.wire;

/** Where on the wire an error code may appear. */
public enum ErrorScope {
    /** The JSON body of a refused WebSocket upgrade, before a session exists. */
    UPGRADE,
    /** The {@code code} of an {@code ERROR} frame; the session then closes. */
    SESSION,
    /** The payload of a {@code CLOSE} frame with {@code RST}; only that stream ends. */
    STREAM
}
