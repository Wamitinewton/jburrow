package io.jburrow.server.core.tunnel.application;

import io.jburrow.server.core.portpool.application.PortReservationService;
import io.jburrow.server.core.portpool.domain.PortPool;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.routing.application.SubdomainRoutes;
import io.jburrow.server.core.routing.domain.Blocklist;
import io.jburrow.server.core.routing.domain.NameGenerator;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * The registration terms, port pool and route table tests register tunnels with, as a developer's machine would have
 * them.
 */
public final class RegistrationFixtures {

    public static final String HTTP_DOMAIN = "localhost";
    public static final int HTTP_PORT = 7080;
    public static final Duration RESUME_WINDOW = Duration.ofSeconds(120);
    public static final Duration REUSE_DELAY = Duration.ofSeconds(60);
    public static final Duration QUARANTINE = Duration.ofMinutes(10);

    private RegistrationFixtures() {}

    /** HTTP tunnels on {@code http://<name>.localhost:7080}. */
    public static RegistrationPolicy policy(int maxTunnels, String tcpPublicHost) {
        return new RegistrationPolicy(
                maxTunnels, tcpPublicHost, new RegistrationPolicy.HttpPublic(HTTP_DOMAIN, "http", HTTP_PORT));
    }

    public static SubdomainRoutes<Principal, TunnelId> routes() {
        return routes(RESUME_WINDOW, InstantSource.system());
    }

    public static SubdomainRoutes<Principal, TunnelId> routes(Duration hold, InstantSource clock) {
        return new SubdomainRoutes<>(Blocklist.defaults(Set.of()), new NameGenerator(new Random(7)), hold, clock);
    }

    public static PortReservationService<Principal> ports(PortRange range, RandomGenerator random) {
        return ports(range, random, InstantSource.system());
    }

    public static PortReservationService<Principal> ports(
            PortRange range, RandomGenerator random, InstantSource clock) {
        return new PortReservationService<>(
                new PortPool<>(range, REUSE_DELAY, QUARANTINE, RESUME_WINDOW, random, clock));
    }
}
