package io.jburrow.protocol.stream;

/**
 * Something that happens to a stream. {@code *_RECEIVED} events come from the peer, {@code *_SENT} events from this
 * side. {@code CLOSE} is the half-close; {@code RST} is {@code CLOSE} with the {@code RST} flag.
 */
public enum StreamEvent {
    DATA_RECEIVED,
    CLOSE_RECEIVED,
    RST_RECEIVED,
    DATA_SENT,
    CLOSE_SENT,
    RST_SENT;

    public boolean isReceived() {
        return this == DATA_RECEIVED || this == CLOSE_RECEIVED || this == RST_RECEIVED;
    }
}
