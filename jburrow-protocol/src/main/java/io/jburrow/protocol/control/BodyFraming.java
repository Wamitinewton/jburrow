package io.jburrow.protocol.control;

import java.util.Optional;

/**
 * How the receiver of an HTTP message frames its body again for its own connection. The body itself crosses the tunnel
 * without HTTP/1.1 framing, as {@code DATA}, ended by {@code CLOSE} ({@code docs/protocol.md} §9.7).
 */
public enum BodyFraming {
    /** No body. The sender's {@code CLOSE} follows the head at once. */
    EMPTY("empty"),
    /** Exactly {@code content-length} bytes. */
    LENGTH("length"),
    /** A body of unknown length, ended by {@code CLOSE}. */
    CHUNKED("chunked");

    private final String wireName;

    BodyFraming(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** The framing for a wire name. The match is exact: no case folding, no trimming. */
    public static Optional<BodyFraming> fromWireName(String wireName) {
        for (BodyFraming framing : values()) {
            if (framing.wireName.equals(wireName)) {
                return Optional.of(framing);
            }
        }
        return Optional.empty();
    }
}
