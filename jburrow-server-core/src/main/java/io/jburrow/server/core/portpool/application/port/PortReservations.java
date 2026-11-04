package io.jburrow.server.core.portpool.application.port;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.OptionalInt;

/**
 * Hands out tunnel ports. Thread-safe; may be called from any thread.
 *
 * @param <P> who reserves a port; a released port is held for the same principal
 */
public interface PortReservations<P> {

    /**
     * Reserves {@code requestedPort} for {@code principal} if present, or any free port. A requested port held for
     * {@code principal} is granted; one held for someone else is not.
     *
     * @throws ProtocolException {@link ErrorCode#PORT_UNAVAILABLE} if the requested port is outside the range, reserved,
     *     or held for another principal, {@link ErrorCode#PORT_POOL_EXHAUSTED} if no port is free
     */
    Reservation reserve(OptionalInt requestedPort, P principal);

    /**
     * Ends the reservation and holds the port for its principal for the resume window. Does nothing if the reservation
     * no longer holds it.
     */
    void release(Reservation reservation);

    /** Holds the port out of the pool for a while, for nobody, because something else on the host is using it. */
    void quarantine(Reservation reservation);

    PortPoolSnapshot snapshot();
}
