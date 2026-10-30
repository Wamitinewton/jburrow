package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.server.core.routing.application.port.RouteLookup;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** What every {@link ExchangeHandler} of one edge shares. Thread-safe: read from every edge loop. */
public final class ExchangeServices {

    private final RouteLookup<TunnelId> routes;
    private final ExchangeSessions sessions;
    private final EdgeErrorPage pages;
    private final RequestHeads heads;
    private final long maxRequestBody;
    private final BooleanSupplier shuttingDown;
    private final TunnelMetrics metrics;

    /**
     * @param routes the route table, read once per request
     * @param sessions the session serving a tunnel
     * @param maxRequestBody the largest request body the edge forwards, in bytes
     * @param shuttingDown whether the server is stopping, so new requests get {@code SERVER_SHUTTING_DOWN}
     */
    public ExchangeServices(
            RouteLookup<TunnelId> routes,
            ExchangeSessions sessions,
            EdgeErrorPage pages,
            ControlJson json,
            long maxRequestBody,
            BooleanSupplier shuttingDown,
            TunnelMetrics metrics) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.heads = new RequestHeads(json, maxRequestBody);
        this.maxRequestBody = maxRequestBody;
        this.shuttingDown = Objects.requireNonNull(shuttingDown, "shuttingDown");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    RouteLookup<TunnelId> routes() {
        return routes;
    }

    ExchangeSessions sessions() {
        return sessions;
    }

    EdgeErrorPage pages() {
        return pages;
    }

    RequestHeads heads() {
        return heads;
    }

    long maxRequestBody() {
        return maxRequestBody;
    }

    boolean shuttingDown() {
        return shuttingDown.getAsBoolean();
    }

    TunnelMetrics metrics() {
        return metrics;
    }
}
