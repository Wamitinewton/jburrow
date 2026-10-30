package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.jburrow.server.core.portpool.application.port.PortReservations;
import io.jburrow.server.core.routing.application.port.RouteLookup;
import io.jburrow.server.core.tunnel.application.RegisterTunnels;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.jburrow.server.core.tunnel.application.StreamQuota;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * What the engine is given by the composition root.
 *
 * @param streamQuota the server-wide stream cap, shared by every session
 * @param routes the route table registration claims names in, read by the edge for every HTTP request
 * @param blockingExecutor runs everything that may block, off the event loops; virtual threads in production
 */
public record EngineDependencies(
        RegisterTunnels registerTunnels,
        SessionAdmission admission,
        StreamQuota streamQuota,
        PortReservations ports,
        RouteLookup<TunnelId> routes,
        TunnelMetrics metrics,
        Executor blockingExecutor,
        TcpSettings tcp) {

    public EngineDependencies {
        Objects.requireNonNull(registerTunnels, "registerTunnels");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(streamQuota, "streamQuota");
        Objects.requireNonNull(ports, "ports");
        Objects.requireNonNull(routes, "routes");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        Objects.requireNonNull(tcp, "tcp");
    }
}
