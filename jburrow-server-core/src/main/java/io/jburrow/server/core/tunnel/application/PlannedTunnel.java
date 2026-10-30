package io.jburrow.server.core.tunnel.application;

import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.server.core.portpool.application.port.Reservation;
import io.jburrow.server.core.tunnel.domain.Tunnel;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.util.Objects;

/** A requested tunnel with what makes it public reserved: a port for a TCP tunnel, a name for an HTTP tunnel. */
public sealed interface PlannedTunnel {

    TunnelRequest request();

    TunnelId id();

    /** The tunnel once it is public, on the terms of {@code policy}. */
    Tunnel toTunnel(RegistrationPolicy policy);

    /** A TCP tunnel with its port reserved, not yet bound. */
    record Tcp(TunnelRequest request, Reservation reservation, TunnelId id) implements PlannedTunnel {

        public Tcp {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(reservation, "reservation");
            Objects.requireNonNull(id, "id");
            if (request.kind() != TunnelKind.TCP) {
                throw new IllegalArgumentException("A port is reserved for a TCP tunnel, not " + request.kind());
            }
        }

        @Override
        public Tunnel toTunnel(RegistrationPolicy policy) {
            return Tunnel.tcp(id, request.ref(), policy.publicHost(), reservation.port());
        }
    }

    /** An HTTP tunnel with its name claimed in the route table. It has no listener, so it is public as it stands. */
    record Http(TunnelRequest request, SubdomainName name, TunnelId id) implements PlannedTunnel {

        public Http {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(id, "id");
            if (request.kind() != TunnelKind.HTTP) {
                throw new IllegalArgumentException("A name is claimed for an HTTP tunnel, not " + request.kind());
            }
        }

        @Override
        public Tunnel toTunnel(RegistrationPolicy policy) {
            RegistrationPolicy.HttpPublic http = policy.http();
            return Tunnel.http(id, request.ref(), name, http.domain(), http.scheme(), http.port());
        }
    }
}
