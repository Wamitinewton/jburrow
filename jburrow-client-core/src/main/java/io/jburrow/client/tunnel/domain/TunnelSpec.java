package io.jburrow.client.tunnel.domain;

import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * One tunnel a client asks for: what the server sees in {@code REGISTER} ({@code ref}, {@code kind} and an optional
 * {@code remotePort}), and the local target only the client knows.
 *
 * <p>The protocol's field rules for a {@code REGISTER} entry are checked here, so a bad value fails when the spec is
 * built rather than as a {@code VALIDATION_FAILED} from the server.
 */
public record TunnelSpec(String ref, TunnelKind kind, LocalTarget target, OptionalInt remotePort) {

    public TunnelSpec {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(remotePort, "remotePort");
        Objects.requireNonNull(ref, "ref");
        toRequest(ref, kind, remotePort);
    }

    /** A TCP tunnel to {@code target}, on a public port the server chooses. */
    public static TunnelSpec tcp(String ref, LocalTarget target) {
        return new TunnelSpec(ref, TunnelKind.TCP, target, OptionalInt.empty());
    }

    /** The same tunnel, asking the server for public port {@code port}. */
    public TunnelSpec remotePort(int port) {
        return new TunnelSpec(ref, kind, target, OptionalInt.of(port));
    }

    /** The {@code REGISTER} entry for this tunnel. */
    public TunnelRequest toRequest() {
        return toRequest(ref, kind, remotePort);
    }

    private static TunnelRequest toRequest(String ref, TunnelKind kind, OptionalInt remotePort) {
        try {
            return new TunnelRequest(ref, kind, remotePort.isPresent() ? remotePort.getAsInt() : null);
        } catch (ProtocolException e) {
            throw new IllegalArgumentException("Tunnel " + e.getMessage(), e);
        }
    }
}
