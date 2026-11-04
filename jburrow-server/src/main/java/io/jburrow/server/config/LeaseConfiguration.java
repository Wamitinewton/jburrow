package io.jburrow.server.config;

import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.api.lease.adapter.persistence.JpaLeaseStore;
import io.jburrow.server.api.lease.adapter.persistence.LeaseJpaRepository;
import io.jburrow.server.core.lease.application.LeaseKeeper;
import io.jburrow.server.core.lease.application.port.LeaseEvents;
import io.jburrow.server.core.lease.application.port.LeaseStore;
import io.jburrow.server.core.portpool.application.PortReservationService;
import io.jburrow.server.core.routing.application.SubdomainRoutes;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import io.jburrow.server.observability.LeaseWriteReporter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.InstantSource;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Constructs the lease keeper from {@code jburrow.lease.*} and {@code jburrow.session.*}, over the route table and the
 * port pool, writing to Postgres under this start's instance id. {@link EdgeServerLifecycle} seeds it before the edge
 * binds and runs its {@link LeaseSchedule}.
 */
@Configuration(proxyBeanMethods = false)
class LeaseConfiguration {

    /** A principal's name is its lease string; a row whose string isn't a valid name is dropped at seeding. */
    static final LeaseKeeper.Principals<Principal> PRINCIPALS =
            new LeaseKeeper.Principals<>(Principal::name, LeaseConfiguration::principal);

    @Bean
    LeaseStore leaseStore(LeaseJpaRepository repository) {
        return new JpaLeaseStore(repository);
    }

    @Bean
    LeaseEvents leaseEvents(MeterRegistry registry) {
        return new LeaseWriteReporter(registry, InstantSource.system());
    }

    @Bean
    LeaseKeeper<Principal> leaseKeeper(
            SubdomainRoutes<Principal, TunnelId> routes,
            PortReservationService<Principal> ports,
            LeaseStore store,
            LeaseEvents events,
            InstanceId instance,
            JBurrowProperties properties) {
        return new LeaseKeeper<>(
                routes,
                ports,
                store,
                events,
                new LeaseKeeper.Settings(
                        instance.value(),
                        properties.session().resumeWindow(),
                        properties.lease().refresh()),
                PRINCIPALS,
                InstantSource.system());
    }

    @Bean
    LeaseSchedule leaseSchedule(LeaseKeeper<Principal> keeper) {
        return new LeaseSchedule(keeper);
    }

    static Optional<Principal> principal(String name) {
        try {
            return Optional.of(new Principal(name));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
