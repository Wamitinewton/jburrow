package io.jburrow.server.core.tunnel.application.port;

import io.jburrow.server.core.tunnel.domain.Principal;
import java.util.Optional;

/** Checks an authtoken. May block, so it is never called on an event loop. */
public interface AuthtokenVerifier {

    /** The token's owner, or empty if the token is malformed or unknown. */
    Optional<Principal> verify(String authtoken);
}
