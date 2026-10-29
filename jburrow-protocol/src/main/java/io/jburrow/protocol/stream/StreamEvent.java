package io.jburrow.protocol.stream;

/**
 * Something that happens to a stream. {@code *_RECEIVED} events come from the peer, {@code *_SENT} events from this
 * side. {@code CLOSE} is the half-close; {@code RST} is {@code CLOSE} with the {@code RST} flag. {@code WINDOW_UPDATE}
 * here is one on this stream, never on stream 0. {@code HEADERS} is a response head on an HTTP stream, which the
 * lifecycle treats as it treats {@code DATA}: it is the start of the client's direction.
 */
public enum StreamEvent {
    DATA_RECEIVED,
    HEADERS_RECEIVED,
    CLOSE_RECEIVED,
    RST_RECEIVED,
    WINDOW_UPDATE_RECEIVED,
    DATA_SENT,
    HEADERS_SENT,
    CLOSE_SENT,
    RST_SENT,
    WINDOW_UPDATE_SENT;

    public boolean isReceived() {
        return this == DATA_RECEIVED
                || this == HEADERS_RECEIVED
                || this == CLOSE_RECEIVED
                || this == RST_RECEIVED
                || this == WINDOW_UPDATE_RECEIVED;
    }
}
