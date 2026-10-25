package io.jburrow.server.observability;

import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * {@code UP} while the edge accepts connections. Shown under {@code /actuator/health} as {@code edge} and kept out of
 * readiness: a bound edge is a startup condition, and startup already fails without it.
 */
public final class EdgeHealthIndicator implements HealthIndicator {

    private final EdgeServer edge;
    private final SessionAdmission admission;

    public EdgeHealthIndicator(EdgeServer edge, SessionAdmission admission) {
        this.edge = Objects.requireNonNull(edge, "edge");
        this.admission = Objects.requireNonNull(admission, "admission");
    }

    @Override
    public Health health() {
        if (!edge.isAccepting()) {
            return Health.down().withDetail("transport", edge.transportName()).build();
        }
        return Health.up()
                .withDetail("transport", edge.transportName())
                .withDetail("port", edge.boundPort())
                .withDetail("sessions", admission.activeSessions())
                .withDetail("handshaking", admission.handshaking())
                .build();
    }
}
