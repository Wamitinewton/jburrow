package io.jburrow.server.core.tunnel.application.port;

import java.util.Optional;

/**
 * Checks an authtoken. May block, so it is never called on an event loop.
 *
 * <p>It answers with the owner's name rather than a {@code Principal}: an implementation lives in another feature, and
 * features reach each other only through ports, so the domain type stays inside the engine.
 */
public interface AuthtokenVerifier {

    /** The name of the token's owner, or empty if the token is malformed or unknown. */
    Optional<String> verify(String authtoken);
}
