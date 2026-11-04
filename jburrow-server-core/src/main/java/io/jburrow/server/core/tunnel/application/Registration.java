package io.jburrow.server.core.tunnel.application;

import io.jburrow.server.core.tunnel.domain.Principal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An accepted {@code REGISTER}: who registered, a planned tunnel per request entry, in request order, and, in a session
 * with {@code resume}, the resume token issued for its reply. The token is for the reply only: whoever sends the reply
 * keeps the registration {@link #withoutResumeToken() without it}, so the server holds no token for the session's
 * life.
 */
public record Registration(
        Principal principal, List<PlannedTunnel> tunnels, Optional<ResumeTokens.Issued> resumeToken) {

    public Registration {
        Objects.requireNonNull(principal, "principal");
        tunnels = List.copyOf(tunnels);
        Objects.requireNonNull(resumeToken, "resumeToken");
    }

    /** A registration without a resume token. */
    public Registration(Principal principal, List<PlannedTunnel> tunnels) {
        this(principal, tunnels, Optional.empty());
    }

    /** The same registration with {@code tunnels} in place of its own, such as after a port was replaced. */
    public Registration withTunnels(List<PlannedTunnel> tunnels) {
        return new Registration(principal, tunnels, resumeToken);
    }

    public Registration withoutResumeToken() {
        return resumeToken.isEmpty() ? this : new Registration(principal, tunnels);
    }
}
