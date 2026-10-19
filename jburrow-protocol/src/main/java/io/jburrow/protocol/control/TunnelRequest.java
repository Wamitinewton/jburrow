package io.jburrow.protocol.control;

/**
 * One tunnel a client asks for in {@code REGISTER}. {@code ref} is chosen by the client and correlates the reply and
 * errors; {@code remotePort} is optional and may be null.
 */
public record TunnelRequest(String ref, TunnelKind kind, Integer remotePort) {

    public TunnelRequest {
        Fields.matching(ref, Fields.TUNNEL_REF, "ref");
        Fields.required(kind, "kind");
        Fields.optionalRange(remotePort, Fields.MIN_PORT, Fields.MAX_PORT, "remotePort");
    }
}
