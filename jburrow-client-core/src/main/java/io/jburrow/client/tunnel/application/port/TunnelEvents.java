package io.jburrow.client.tunnel.application.port;

import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.wire.ErrorCode;
import java.util.List;
import java.util.Optional;

/**
 * What happens to a session, for whoever embeds the client: the CLI prints it, a test asserts on it. Every method does
 * nothing by default.
 *
 * <p>Calls arrive on the client's event loop, one at a time and in order. A listener must return quickly and must not
 * block: the loop carries every byte of the session. An exception thrown by a listener is logged and otherwise
 * ignored.
 *
 * <p>A session that never establishes reports nothing here; {@code JBurrowClient.start()} throws instead. An
 * established session reports {@link #onEstablished} first and {@link #onClosed} last, exactly once each, and every
 * {@link #onStreamOpened} is followed by exactly one {@link #onStreamClosed} for the same stream before
 * {@link #onClosed}.
 */
public interface TunnelEvents {

    /** The server's {@code REGISTER} reply arrived: the tunnels are public. */
    default void onEstablished(String sessionId, List<RegisteredTunnel> tunnels) {}

    /**
     * The server opened a stream for a visitor, and the client is connecting it to the tunnel's local target.
     *
     * @param remoteAddress the visitor's IP address as the server saw it
     */
    default void onStreamOpened(int streamId, String tunnelRef, String remoteAddress, int remotePort) {}

    /**
     * A stream ended.
     *
     * @param bytesIn bytes from the visitor to the local target
     * @param bytesOut bytes from the local target to the visitor
     * @param reset the reset code if the stream was reset, by either side, rather than closed in both directions; for
     *     example {@code LOCAL_SERVICE_UNREACHABLE} when nothing listened on the local target
     */
    default void onStreamClosed(
            int streamId, long bytesIn, long bytesOut, long durationMillis, Optional<ErrorCode> reset) {}

    /** The session ended. Nothing follows. */
    default void onClosed(TunnelClosed closed) {}
}
