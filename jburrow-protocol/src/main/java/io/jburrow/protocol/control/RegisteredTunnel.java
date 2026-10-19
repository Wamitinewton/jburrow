package io.jburrow.protocol.control;

import java.util.UUID;

/**
 * One tunnel the server created, in its {@code REGISTER} reply. {@code url} is always
 * {@code <kind>://<publicHost>:<publicPort>}; {@link #of} builds it.
 */
public record RegisteredTunnel(
        String ref, UUID tunnelId, TunnelKind kind, String publicHost, int publicPort, String url) {

    public RegisteredTunnel {
        Fields.matching(ref, Fields.TUNNEL_REF, "ref");
        Fields.required(tunnelId, "tunnelId");
        Fields.required(kind, "kind");
        Fields.host(publicHost, "publicHost");
        Fields.range(publicPort, Fields.MIN_PORT, Fields.MAX_PORT, "publicPort");
        if (!urlFor(kind, publicHost, publicPort).equals(Fields.required(url, "url"))) {
            throw Fields.invalid("url", "must be <kind>://<publicHost>:<publicPort>");
        }
    }

    public static RegisteredTunnel of(String ref, UUID tunnelId, TunnelKind kind, String publicHost, int publicPort) {
        return new RegisteredTunnel(ref, tunnelId, kind, publicHost, publicPort, urlFor(kind, publicHost, publicPort));
    }

    private static String urlFor(TunnelKind kind, String publicHost, int publicPort) {
        return kind.wireName() + "://" + publicHost + ":" + publicPort;
    }
}
