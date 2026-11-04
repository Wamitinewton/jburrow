package io.jburrow.protocol.control;

import java.util.List;

/**
 * {@code REGISTER}, client to server: the authtoken, the tunnels the client wants and, with {@code resume}, the token
 * of its previous session. The record allows up to 32 tunnels; the session's {@code maxTunnels} is the server's to
 * enforce, as {@code TUNNEL_LIMIT_EXCEEDED}. {@code resume} is optional and may be null.
 *
 * <p>{@link #toString()} never contains the authtoken or the resume token.
 */
public record Register(String authtoken, List<TunnelRequest> tunnels, ResumeToken resume) implements ControlMessage {

    private static final String TOKEN_PREFIX = "jb_";

    public Register {
        Fields.length(authtoken, 1, Fields.MAX_AUTHTOKEN_LENGTH, "authtoken");
        tunnels = Fields.list(tunnels, 1, Fields.MAX_TUNNELS, "tunnels");
        Fields.requireUnique(tunnels, TunnelRequest::ref, "tunnels", ".ref");
    }

    /** A request without a resume token. */
    public Register(String authtoken, List<TunnelRequest> tunnels) {
        this(authtoken, tunnels, null);
    }

    @Override
    public String toString() {
        String token = authtoken.startsWith(TOKEN_PREFIX) ? TOKEN_PREFIX + "***" : "***";
        return "Register[authtoken=" + token + ", tunnels=" + tunnels + ", resume=" + resume + "]";
    }
}
