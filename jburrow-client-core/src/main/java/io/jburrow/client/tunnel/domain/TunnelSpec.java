package io.jburrow.client.tunnel.domain;

import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One tunnel a client asks for: what the server sees in {@code REGISTER} ({@code ref}, {@code kind}, and an optional
 * {@code remotePort} for TCP or {@code subdomain} for HTTP), and what only the client knows: the local target and, for
 * HTTP, what it sends the local service as {@code host}.
 *
 * <p>The protocol's field rules for a {@code REGISTER} entry are checked here, so a bad value fails when the spec is
 * built rather than as a {@code VALIDATION_FAILED} from the server.
 */
public record TunnelSpec(
        String ref,
        TunnelKind kind,
        LocalTarget target,
        OptionalInt remotePort,
        Optional<SubdomainName> subdomain,
        HostHeader hostHeader) {

    public TunnelSpec {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(remotePort, "remotePort");
        Objects.requireNonNull(subdomain, "subdomain");
        Objects.requireNonNull(hostHeader, "hostHeader");
        Objects.requireNonNull(ref, "ref");
        if (hostHeader != HostHeader.KEEP && kind != TunnelKind.HTTP) {
            throw new IllegalArgumentException("The host header can only be rewritten for an HTTP tunnel");
        }
        toRequest(ref, kind, remotePort, subdomain);
    }

    /** A tunnel without a requested port or name, sending {@code host} as the visitor did. */
    public TunnelSpec(String ref, TunnelKind kind, LocalTarget target, OptionalInt remotePort) {
        this(ref, kind, target, remotePort, Optional.empty(), HostHeader.KEEP);
    }

    /** A TCP tunnel to {@code target}, on a public port the server chooses. */
    public static TunnelSpec tcp(String ref, LocalTarget target) {
        return new TunnelSpec(ref, TunnelKind.TCP, target, OptionalInt.empty());
    }

    /** An HTTP tunnel to {@code target}, on a name the server chooses. */
    public static TunnelSpec http(String ref, LocalTarget target) {
        return new TunnelSpec(ref, TunnelKind.HTTP, target, OptionalInt.empty());
    }

    /** The same tunnel, asking the server for public port {@code port}. */
    public TunnelSpec remotePort(int port) {
        return new TunnelSpec(ref, kind, target, OptionalInt.of(port), subdomain, hostHeader);
    }

    /** The same HTTP tunnel, asking the server for {@code name}. */
    public TunnelSpec subdomain(SubdomainName name) {
        return new TunnelSpec(
                ref, kind, target, remotePort, Optional.of(Objects.requireNonNull(name, "name")), hostHeader);
    }

    /** The same HTTP tunnel, sending the local service {@code host} as {@code option} says. */
    public TunnelSpec hostHeader(HostHeader option) {
        return new TunnelSpec(ref, kind, target, remotePort, subdomain, option);
    }

    /** The {@code REGISTER} entry for this tunnel. */
    public TunnelRequest toRequest() {
        return toRequest(ref, kind, remotePort, subdomain);
    }

    private static TunnelRequest toRequest(
            String ref, TunnelKind kind, OptionalInt remotePort, Optional<SubdomainName> subdomain) {
        try {
            return new TunnelRequest(
                    ref,
                    kind,
                    subdomain.map(SubdomainName::value).orElse(null),
                    remotePort.isPresent() ? remotePort.getAsInt() : null);
        } catch (ProtocolException e) {
            throw new IllegalArgumentException("Tunnel " + e.getMessage(), e);
        }
    }
}
