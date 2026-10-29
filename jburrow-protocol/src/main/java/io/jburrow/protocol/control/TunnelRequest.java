package io.jburrow.protocol.control;

/**
 * One tunnel a client asks for in {@code REGISTER}. {@code ref} is chosen by the client and correlates the reply and
 * errors. {@code subdomain} asks for an HTTP tunnel's name and {@code remotePort} for a TCP tunnel's port; each is
 * optional, allowed only with its kind, and null when absent.
 */
public record TunnelRequest(String ref, TunnelKind kind, String subdomain, Integer remotePort) {

    public TunnelRequest {
        Fields.matching(ref, Fields.TUNNEL_REF, "ref");
        Fields.required(kind, "kind");
        Fields.optionalSubdomain(subdomain, "subdomain");
        Fields.optionalRange(remotePort, Fields.MIN_PORT, Fields.MAX_PORT, "remotePort");
        if (subdomain != null && kind != TunnelKind.HTTP) {
            throw Fields.invalid("subdomain", "is only allowed with kind http");
        }
        if (remotePort != null && kind != TunnelKind.TCP) {
            throw Fields.invalid("remotePort", "is only allowed with kind tcp");
        }
    }

    /** A request without a subdomain, for a TCP tunnel. */
    public TunnelRequest(String ref, TunnelKind kind, Integer remotePort) {
        this(ref, kind, null, remotePort);
    }
}
