package io.jburrow.server.core.tunnel.adapter.netty.control;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.tunnel.application.Registration;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.netty.util.concurrent.Future;
import java.util.List;

/**
 * Binds and unbinds the public listeners of a session's tunnels, on the session's event loop.
 *
 * <p>Neither method releases a port reservation. The session releases its registration once {@link #unbindAll} has
 * completed, so a port is never returned to the pool while its listener might still be bound.
 */
public interface TunnelListeners {

    /**
     * Binds a listener for every planned TCP tunnel of {@code registration}; a planned HTTP tunnel has none. If a bind
     * has to move a tunnel to another port, the implementation stores the updated registration with
     * {@link SessionContext#registration(Registration)}, so the session releases what it actually holds.
     *
     * @return completes on the session's event loop with the tunnels in the registration's order, or fails with a
     *     {@link ProtocolException} for the client (such as {@code PORT_UNAVAILABLE}), or any other exception for an
     *     internal error. A failure leaves whatever was bound for {@link #unbindAll}.
     */
    Future<List<Tunnel>> bind(SessionContext session, Registration registration);

    /**
     * Closes every listener of the session, including one whose bind hasn't completed yet. Called once, when the
     * session's connection has closed.
     *
     * @return completes on the session's event loop once every listener is closed
     */
    Future<?> unbindAll(SessionContext session);

    /** Refuses every registration with {@code INTERNAL_ERROR}, for a server that has no TCP tunnel listeners. */
    static TunnelListeners unavailable() {
        return new TunnelListeners() {
            @Override
            public Future<List<Tunnel>> bind(SessionContext session, Registration registration) {
                return session.channel()
                        .eventLoop()
                        .newFailedFuture(ProtocolException.session(
                                ErrorCode.INTERNAL_ERROR, "TCP tunnels are not available on this server."));
            }

            @Override
            public Future<?> unbindAll(SessionContext session) {
                return session.channel().eventLoop().newSucceededFuture(null);
            }
        };
    }
}
