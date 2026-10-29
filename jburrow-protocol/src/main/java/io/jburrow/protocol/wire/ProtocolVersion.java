package io.jburrow.protocol.wire;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The protocol's major version, carried as the WebSocket subprotocol. {@code jburrow.v1-draft.N} until v1.0.0, then
 * {@code jburrow.v1}. Tokens compare exactly, as RFC 6455 requires.
 */
public final class ProtocolVersion {

    public static final String CURRENT = "jburrow.v1-draft.2";

    private static final List<String> SUPPORTED = List.of(CURRENT);
    private static final Set<Capability> CAPABILITIES = Collections.unmodifiableSet(EnumSet.allOf(Capability.class));

    private ProtocolVersion() {}

    /** Every token this implementation speaks, most preferred first. */
    public static List<String> supported() {
        return SUPPORTED;
    }

    /** The first offered token that is supported, so the peer's preference order wins. */
    public static Optional<String> select(List<String> offered) {
        return offered.stream().filter(SUPPORTED::contains).findFirst();
    }

    /** Every capability this implementation knows. */
    public static Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    /**
     * The server's answer to the client's {@code capabilities}: the offered names it knows and has {@code enabled}, in
     * the order of {@link Capability}'s constants. Unknown names are ignored, as {@code docs/protocol.md} §4.2 requires.
     */
    public static List<String> negotiate(List<String> offered, Set<Capability> enabled) {
        Objects.requireNonNull(enabled, "enabled");
        Set<Capability> agreed = EnumSet.noneOf(Capability.class);
        for (String name : Objects.requireNonNull(offered, "offered")) {
            Capability.fromWireName(name).filter(enabled::contains).ifPresent(agreed::add);
        }
        return agreed.stream().map(Capability::wireName).toList();
    }

    /**
     * The tokens in a {@code Sec-WebSocket-Protocol} value, in order, trimmed, with empty entries and repeats dropped.
     * A {@code null} value yields an empty list.
     */
    public static List<String> parseHeader(String secWebSocketProtocol) {
        if (secWebSocketProtocol == null) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String entry : secWebSocketProtocol.split(",")) {
            String token = entry.strip();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }
}
