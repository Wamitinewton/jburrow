package io.jburrow.server.core.tunnel.application.port;

import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.ResumeTokenHash;
import io.jburrow.server.core.tunnel.domain.SessionId;
import java.time.Duration;

/**
 * The sessions live on this server, as far as a resume token can name one: step 2a of {@code docs/protocol.md} §8.4.
 * Blocks, so it is called on the registration's virtual thread, never on an event loop.
 */
public interface LiveSessions {

    /**
     * Ends the live session whose resume token hashes to {@code tokenHash} with {@code SESSION_REPLACED}, if it belongs
     * to {@code principal} and isn't {@code replacement} itself, and waits until its tunnels are released or
     * {@code timeout} passes. A token works at most once.
     *
     * @param replacement the session presenting the token, for the record of who replaced whom
     * @return whether a session was ended; a token of no live session, or of another principal's, is not an error
     */
    boolean replace(ResumeTokenHash tokenHash, Principal principal, SessionId replacement, Duration timeout);

    /** Sessions that no token ever names, for a server or a test without {@code resume}. */
    static LiveSessions none() {
        return (tokenHash, principal, replacement, timeout) -> false;
    }
}
