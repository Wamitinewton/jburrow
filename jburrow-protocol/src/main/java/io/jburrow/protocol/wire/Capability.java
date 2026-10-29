package io.jburrow.protocol.wire;

import java.util.Optional;

/**
 * An optional feature of a protocol version, negotiated in {@code HELLO} ({@code docs/protocol.md} §4.2). What a
 * capability introduces may only be sent in a session whose server listed it.
 */
public enum Capability {
    /** HTTP tunnels: the {@code "http"} kind, {@code HEADERS}, and the fields and codes that go with them (§4.2). */
    HTTP("http");

    private final String wireName;

    Capability(String wireName) {
        this.wireName = wireName;
    }

    /** The name in a {@code HELLO}'s {@code capabilities}. */
    public String wireName() {
        return wireName;
    }

    /** The capability for a wire name, or empty for one this implementation doesn't know. The match is exact. */
    public static Optional<Capability> fromWireName(String wireName) {
        for (Capability capability : values()) {
            if (capability.wireName.equals(wireName)) {
                return Optional.of(capability);
            }
        }
        return Optional.empty();
    }
}
