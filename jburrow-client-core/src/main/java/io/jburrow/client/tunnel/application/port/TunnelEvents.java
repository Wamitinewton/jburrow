package io.jburrow.client.tunnel.application.port;

import io.jburrow.client.tunnel.domain.ExchangeSummary;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.wire.ErrorCode;
import java.time.Duration;
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
 * <p>A client whose first session never establishes reports nothing here; {@code JBurrowClient.start()} throws instead.
 * Otherwise the first session reports {@link #onEstablished}, and {@link #onClosed} comes last, exactly once each. In
 * between, each session that ends while the client will try again reports {@link #onDisconnected}, and so does each
 * attempt that fails, with the delay before the next one; {@link #onReconnected} reports the session that follows. Every
 * {@link #onStreamOpened} is followed by exactly one {@link #onStreamClosed} for the same stream, before its session's
 * {@link #onDisconnected} or the client's {@link #onClosed}. A stream of an HTTP tunnel reports
 * {@link #onExchangeCompleted} once, just before its {@link #onStreamClosed}.
 */
public interface TunnelEvents {

    /** The first session's {@code REGISTER} reply arrived: the tunnels are public. */
    default void onEstablished(String sessionId, List<RegisteredTunnel> tunnels) {}

    /**
     * A session ended, or an attempt to start the next one failed, and the client tries again after {@code retryIn}.
     *
     * @param reason why: for a session ended after {@code GOAWAY}, {@code SERVER_ERROR} with {@code SERVER_SHUTTING_DOWN}
     */
    default void onDisconnected(TunnelClosed reason, Duration retryIn) {}

    /**
     * A later session's {@code REGISTER} reply arrived: the tunnels are public again.
     *
     * @param sameUrls whether every tunnel has the URL it had in the session before; false when a name or port the
     *     server had chosen was taken in between, and it chose another
     */
    default void onReconnected(String sessionId, List<RegisteredTunnel> tunnels, boolean sameUrls) {}

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

    /**
     * An exchange of an HTTP tunnel ended: its response was complete, or its stream was reset. Never carries a header
     * value or a body.
     */
    default void onExchangeCompleted(ExchangeSummary exchange) {}

    /** The client stopped: the session ended, and no other will be tried. Nothing follows. */
    default void onClosed(TunnelClosed closed) {}
}
