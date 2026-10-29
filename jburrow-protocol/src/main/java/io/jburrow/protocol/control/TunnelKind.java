package io.jburrow.protocol.control;

import io.jburrow.protocol.wire.Capability;
import java.util.Optional;

/** The kind of a tunnel. {@code "http"} exists only in a session with the {@code http} capability. */
public enum TunnelKind {
    TCP("tcp", null),
    HTTP("http", Capability.HTTP);

    private final String wireName;
    private final Capability capability;

    TunnelKind(String wireName, Capability capability) {
        this.wireName = wireName;
        this.capability = capability;
    }

    /** The lower-case name used on the wire. */
    public String wireName() {
        return wireName;
    }

    /** The capability a session needs before this kind may be sent in it; empty for a kind of the draft itself. */
    public Optional<Capability> capability() {
        return Optional.ofNullable(capability);
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
