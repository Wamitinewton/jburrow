package io.jburrow.server.core.tunnel.application;

import java.util.Objects;
import java.util.Set;

/**
 * The server's terms for a {@code REGISTER}: how many tunnels a session may have, and where each kind is public.
 *
 * @param publicHost the host TCP tunnels are public on, each at its own port
 * @param http where HTTP tunnels are public
 */
public record RegistrationPolicy(int maxTunnels, String publicHost, HttpPublic http) {

    public RegistrationPolicy {
        if (maxTunnels < 1) {
            throw new IllegalArgumentException("maxTunnels must be positive: " + maxTunnels);
        }
        Objects.requireNonNull(publicHost, "publicHost");
        if (publicHost.isBlank()) {
            throw new IllegalArgumentException("publicHost must not be blank");
        }
        Objects.requireNonNull(http, "http");
    }

    /**
     * Where HTTP tunnels are public: {@code <scheme>://<name>.<domain>}, then {@code :<port>} unless it is the scheme's
     * default. The port is the one the public reaches, the ingress's in production, not the edge's.
     */
    public record HttpPublic(String domain, String scheme, int port) {

        private static final Set<String> SCHEMES = Set.of("http", "https");

        public HttpPublic {
            Objects.requireNonNull(domain, "domain");
            if (domain.isBlank()) {
                throw new IllegalArgumentException("domain must not be blank");
            }
            if (!SCHEMES.contains(Objects.requireNonNull(scheme, "scheme"))) {
                throw new IllegalArgumentException("scheme must be http or https: " + scheme);
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port must be 1 to 65535: " + port);
            }
        }
    }
}
