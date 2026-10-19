package io.jburrow.server.core.tunnel.application;

import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.server.core.portpool.application.port.Reservation;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.util.Objects;

/** A requested tunnel with its port reserved, not yet bound. */
public record PlannedTunnel(TunnelRequest request, Reservation reservation, TunnelId id) {

    public PlannedTunnel {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(id, "id");
    }

    /** The tunnel once its listener is bound, public on {@code publicHost} at the reserved port. */
    public Tunnel toTunnel(String publicHost) {
        return new Tunnel(id, request.ref(), request.kind(), publicHost, reservation.port());
    }
}
