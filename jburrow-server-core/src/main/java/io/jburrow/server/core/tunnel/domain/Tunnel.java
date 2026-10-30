package io.jburrow.server.core.tunnel.domain;

import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.TunnelKind;
import java.util.Objects;
import java.util.Set;

/**
 * A tunnel a session created: the client's {@code ref} for it, and where the public reaches it. {@link #tcp} and
 * {@link #http} build one of each kind.
 *
 * @param scheme the scheme of the tunnel's {@code url}: {@code tcp} for a TCP tunnel, {@code http} or {@code https}
 *     for an HTTP tunnel
 * @param subdomain an HTTP tunnel's name, its {@code publicHost}'s first label; null for a TCP tunnel
 */
public record Tunnel(
        TunnelId id,
        String ref,
        TunnelKind kind,
        String publicHost,
        int publicPort,
        String scheme,
        SubdomainName subdomain) {

    private static final String TCP_SCHEME = "tcp";
    private static final Set<String> HTTP_SCHEMES = Set.of("http", "https");

    public Tunnel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(publicHost, "publicHost");
        Objects.requireNonNull(scheme, "scheme");
        if (publicPort < 1 || publicPort > 65_535) {
            throw new IllegalArgumentException("A public port must be 1 to 65535: " + publicPort);
        }
        switch (kind) {
            case TCP -> {
                if (!scheme.equals(TCP_SCHEME) || subdomain != null) {
                    throw new IllegalArgumentException("A TCP tunnel has the tcp scheme and no subdomain");
                }
            }
            case HTTP -> {
                if (!HTTP_SCHEMES.contains(scheme)) {
                    throw new IllegalArgumentException("An HTTP tunnel's scheme is http or https, not " + scheme);
                }
                Objects.requireNonNull(subdomain, "subdomain");
                if (!publicHost.startsWith(subdomain.value() + ".")) {
                    throw new IllegalArgumentException("An HTTP tunnel's public host starts with its subdomain");
                }
            }
        }
    }

    /** A TCP tunnel, public on {@code publicHost} at its own port. */
    public static Tunnel tcp(TunnelId id, String ref, String publicHost, int publicPort) {
        return new Tunnel(id, ref, TunnelKind.TCP, publicHost, publicPort, TCP_SCHEME, null);
    }

    /** An HTTP tunnel, public on {@code <subdomain>.<domain>} at the port every HTTP tunnel shares. */
    public static Tunnel http(
            TunnelId id, String ref, SubdomainName subdomain, String domain, String scheme, int publicPort) {
        Objects.requireNonNull(subdomain, "subdomain");
        Objects.requireNonNull(domain, "domain");
        return new Tunnel(id, ref, TunnelKind.HTTP, subdomain.value() + "." + domain, publicPort, scheme, subdomain);
    }

    /** This tunnel as an entry of the {@code REGISTER} reply. */
    public RegisteredTunnel toRegistered() {
        return switch (kind) {
            case TCP -> RegisteredTunnel.of(ref, id.value(), kind, publicHost, publicPort);
            case HTTP -> RegisteredTunnel.http(ref, id.value(), scheme, publicHost, publicPort);
        };
    }
}
