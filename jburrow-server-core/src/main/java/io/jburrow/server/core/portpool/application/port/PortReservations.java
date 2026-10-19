package io.jburrow.server.core.portpool.application.port;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.OptionalInt;

/** Hands out tunnel ports. Thread-safe; may be called from any thread. */
public interface PortReservations {

    /**
     * Reserves {@code requestedPort} if present, or any free port.
     *
     * @throws ProtocolException {@link ErrorCode#PORT_UNAVAILABLE} if the requested port is outside the range or not
     *     free, {@link ErrorCode#PORT_POOL_EXHAUSTED} if no port is free
     */
    Reservation reserve(OptionalInt requestedPort);

    /** Returns the port. Does nothing if the reservation no longer holds it. */
    void release(Reservation reservation);

    /** Holds the port out of the pool for a while, because something else on the host is using it. */
    void quarantine(Reservation reservation);

    PortPoolSnapshot snapshot();
}
