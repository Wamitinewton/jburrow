package io.jburrow.server.core.tunnel.application;

import io.jburrow.server.core.routing.application.SubdomainRoutes;
import io.jburrow.server.core.routing.domain.Blocklist;
import io.jburrow.server.core.routing.domain.NameGenerator;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Random;
import java.util.Set;

/** The registration terms and route table tests register tunnels with, as a developer's machine would have them. */
public final class RegistrationFixtures {

    public static final String HTTP_DOMAIN = "localhost";
    public static final int HTTP_PORT = 7080;
    public static final Duration NAME_HOLD = Duration.ofSeconds(60);

    private RegistrationFixtures() {}

    /** HTTP tunnels on {@code http://<name>.localhost:7080}. */
    public static RegistrationPolicy policy(int maxTunnels, String tcpPublicHost) {
        return new RegistrationPolicy(
                maxTunnels, tcpPublicHost, new RegistrationPolicy.HttpPublic(HTTP_DOMAIN, "http", HTTP_PORT));
    }

    public static SubdomainRoutes<Principal, TunnelId> routes() {
        return routes(NAME_HOLD, InstantSource.system());
    }

    public static SubdomainRoutes<Principal, TunnelId> routes(Duration hold, InstantSource clock) {
        return new SubdomainRoutes<>(Blocklist.defaults(Set.of()), new NameGenerator(new Random(7)), hold, clock);
    }
}
