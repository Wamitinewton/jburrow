package io.jburrow.protocol.control;

import java.util.List;

/**
 * {@code REGISTER}, client to server: the authtoken and the tunnels the client wants. The record allows up to 32
 * tunnels; the session's {@code maxTunnels} is the server's to enforce, as {@code TUNNEL_LIMIT_EXCEEDED}.
 *
 * <p>{@link #toString()} never contains the authtoken.
 */
public record Register(String authtoken, List<TunnelRequest> tunnels) implements ControlMessage {

    private static final String TOKEN_PREFIX = "jb_";

    public Register {
        Fields.length(authtoken, 1, Fields.MAX_AUTHTOKEN_LENGTH, "authtoken");
        tunnels = Fields.list(tunnels, 1, Fields.MAX_TUNNELS, "tunnels");
        Fields.requireUnique(tunnels, TunnelRequest::ref, "tunnels", ".ref");
    }

    @Override
    public String toString() {
        String token = authtoken.startsWith(TOKEN_PREFIX) ? TOKEN_PREFIX + "***" : "***";
        return "Register[authtoken=" + token + ", tunnels=" + tunnels + "]";
    }
}
