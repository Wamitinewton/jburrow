package io.jburrow.server.core.portpool.application;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.portpool.application.port.PortPoolSnapshot;
import io.jburrow.server.core.portpool.application.port.PortReservations;
import io.jburrow.server.core.portpool.application.port.Reservation;
import io.jburrow.server.core.portpool.domain.PortPool;
import java.util.Objects;
import java.util.OptionalInt;

/** {@link PortReservations} over the in-memory {@link PortPool}. */
public final class PortReservationService implements PortReservations {

    private final PortPool pool;

    public PortReservationService(PortPool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override
    public Reservation reserve(OptionalInt requestedPort) {
        Objects.requireNonNull(requestedPort, "requestedPort");
        if (requestedPort.isEmpty()) {
            return pool.reserveAny()
                    .map(PortReservationService::toReservation)
                    .orElseThrow(() -> ProtocolException.session(
                            ErrorCode.PORT_POOL_EXHAUSTED, "The server has no free port left."));
        }
        int port = requestedPort.getAsInt();
        if (!pool.range().contains(port)) {
            throw ProtocolException.session(
                    ErrorCode.PORT_UNAVAILABLE,
                    "Port " + port + " is outside the server's range " + pool.range() + ".");
        }
        return pool.reserveRequested(port)
                .map(PortReservationService::toReservation)
                .orElseThrow(() ->
                        ProtocolException.session(ErrorCode.PORT_UNAVAILABLE, "Port " + port + " is not available."));
    }

    @Override
    public void release(Reservation reservation) {
        pool.release(reservation.port(), reservation.holder());
    }

    @Override
    public void quarantine(Reservation reservation) {
        pool.quarantine(reservation.port(), reservation.holder());
    }

    @Override
    public PortPoolSnapshot snapshot() {
        PortPool.Snapshot snapshot = pool.snapshot();
        return new PortPoolSnapshot(snapshot.free(), snapshot.reserved(), snapshot.quarantined());
    }

    private static Reservation toReservation(PortPool.Held held) {
        return new Reservation(held.port(), held.holder());
    }
}
