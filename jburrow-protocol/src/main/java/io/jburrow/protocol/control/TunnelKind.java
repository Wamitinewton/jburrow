package io.jburrow.protocol.control;

import java.util.Optional;

/** The kind of a tunnel. {@code jburrow.v1-draft.1} has only TCP tunnels. */
public enum TunnelKind {
    TCP("tcp");

    private final String wireName;

    TunnelKind(String wireName) {
        this.wireName = wireName;
    }

    /** The lower-case name used on the wire and as the URL scheme. */
    public String wireName() {
        return wireName;
    }

    /** The kind for a wire name. The match is exact: no case folding, no trimming. */
    public static Optional<TunnelKind> fromWireName(String wireName) {
        for (TunnelKind kind : values()) {
            if (kind.wireName.equals(wireName)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
