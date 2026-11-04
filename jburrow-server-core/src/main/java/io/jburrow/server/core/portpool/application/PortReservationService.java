package io.jburrow.server.core.portpool.application;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.server.core.portpool.application.port.PortClaimSnapshot;
import io.jburrow.server.core.portpool.application.port.PortHolds;
import io.jburrow.server.core.portpool.application.port.PortPoolSnapshot;
import io.jburrow.server.core.portpool.application.port.PortReservations;
import io.jburrow.server.core.portpool.application.port.Reservation;
import io.jburrow.server.core.portpool.domain.PortPool;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** {@link PortReservations} and {@link PortHolds} over the in-memory {@link PortPool}. */
public final class PortReservationService<P> implements PortReservations<P>, PortHolds<P> {

    private final PortPool<P> pool;

    public PortReservationService(PortPool<P> pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override
    public Reservation reserve(OptionalInt requestedPort, P principal) {
        Objects.requireNonNull(requestedPort, "requestedPort");
        Objects.requireNonNull(principal, "principal");
        if (requestedPort.isEmpty()) {
            return pool.reserveAny(principal)
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
        return pool.reserveRequested(port, principal)
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
        return new PortPoolSnapshot(snapshot.free(), snapshot.reserved(), snapshot.held(), snapshot.quarantined());
    }

    @Override
    public List<PortClaimSnapshot<P>> snapshot(Instant now) {
        Objects.requireNonNull(now, "now");
        long nowMillis = now.toEpochMilli();
        return pool.claims().stream()
                .filter(claim -> claim.active() || claim.heldUntilMillis() > nowMillis)
                .map(PortReservationService::toSnapshot)
                .toList();
    }

    @Override
    public boolean seedHold(int port, P principal, Instant until) {
        Objects.requireNonNull(until, "until");
        return pool.seedHold(port, principal, until.toEpochMilli());
    }

    @Override
    public long version() {
        return pool.version();
    }

    private static Reservation toReservation(PortPool.Held held) {
        return new Reservation(held.port(), held.holder());
    }

    private static <P> PortClaimSnapshot<P> toSnapshot(PortPool.Claim<P> claim) {
        return new PortClaimSnapshot<>(
                claim.port(), claim.principal(), claim.active() ? null : Instant.ofEpochMilli(claim.heldUntilMillis()));
    }
}
