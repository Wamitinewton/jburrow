package io.jburrow.protocol.control;

import java.util.Map;
import java.util.UUID;

/**
 * One tunnel the server created, in its {@code REGISTER} reply. {@code url} follows from the other fields: for a TCP
 * tunnel {@code tcp://<publicHost>:<publicPort>}, for an HTTP tunnel {@code http://} or {@code https://} and
 * {@code <publicHost>}, then {@code :<publicPort>} unless it is that scheme's default. {@link #of} and {@link #http}
 * build it.
 */
public record RegisteredTunnel(
        String ref, UUID tunnelId, TunnelKind kind, String publicHost, int publicPort, String url) {

    private static final String TCP_SCHEME = "tcp";
    private static final Map<String, Integer> HTTP_DEFAULT_PORTS = Map.of("http", 80, "https", 443);

    public RegisteredTunnel {
        Fields.matching(ref, Fields.TUNNEL_REF, "ref");
        Fields.required(tunnelId, "tunnelId");
        Fields.required(kind, "kind");
        Fields.host(publicHost, "publicHost");
        Fields.range(publicPort, Fields.MIN_PORT, Fields.MAX_PORT, "publicPort");
        Fields.required(url, "url");
        boolean matches =
                switch (kind) {
                    case TCP -> url.equals(urlFor(TCP_SCHEME, publicHost, publicPort));
                    case HTTP ->
                        HTTP_DEFAULT_PORTS.keySet().stream()
                                .anyMatch(scheme -> url.equals(urlFor(scheme, publicHost, publicPort)));
                };
        if (!matches) {
            throw Fields.invalid(
                    "url",
                    kind == TunnelKind.TCP
                            ? "must be tcp://<publicHost>:<publicPort>"
                            : "must be http:// or https://<publicHost>, then :<publicPort> unless it is the default");
        }
    }

    /** A TCP tunnel, whose URL is {@code tcp://<publicHost>:<publicPort>}. */
    public static RegisteredTunnel of(String ref, UUID tunnelId, TunnelKind kind, String publicHost, int publicPort) {
        if (kind != TunnelKind.TCP) {
            throw new IllegalArgumentException("A " + kind + " tunnel's URL needs a scheme");
        }
        return new RegisteredTunnel(
                ref, tunnelId, kind, publicHost, publicPort, urlFor(TCP_SCHEME, publicHost, publicPort));
    }

    /** An HTTP tunnel served over {@code scheme}, {@code http} or {@code https}. */
    public static RegisteredTunnel http(String ref, UUID tunnelId, String scheme, String publicHost, int publicPort) {
        if (!HTTP_DEFAULT_PORTS.containsKey(scheme)) {
            throw new IllegalArgumentException("An HTTP tunnel's scheme is http or https, not " + scheme);
        }
        return new RegisteredTunnel(
                ref, tunnelId, TunnelKind.HTTP, publicHost, publicPort, urlFor(scheme, publicHost, publicPort));
    }

    private static String urlFor(String scheme, String publicHost, int publicPort) {
        Integer defaultPort = HTTP_DEFAULT_PORTS.get(scheme);
        return defaultPort != null && defaultPort == publicPort
                ? scheme + "://" + publicHost
                : scheme + "://" + publicHost + ":" + publicPort;
    }
}
