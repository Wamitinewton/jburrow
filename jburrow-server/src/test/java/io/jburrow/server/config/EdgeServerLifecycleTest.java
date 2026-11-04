package io.jburrow.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.server.core.lease.application.LeaseKeeper;
import io.jburrow.server.core.lease.application.port.LeaseEvents;
import io.jburrow.server.core.lease.application.port.LeaseRecord;
import io.jburrow.server.core.lease.application.port.LeaseStore;
import io.jburrow.server.core.portpool.application.PortReservationService;
import io.jburrow.server.core.portpool.domain.PortPool;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.routing.application.SubdomainRoutes;
import io.jburrow.server.core.routing.domain.Blocklist;
import io.jburrow.server.core.routing.domain.NameGenerator;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeSettings;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EngineDependencies;
import io.jburrow.server.core.tunnel.adapter.netty.edge.HttpSettings;
import io.jburrow.server.core.tunnel.adapter.netty.edge.SessionInitializer;
import io.jburrow.server.core.tunnel.adapter.netty.edge.TcpSettings;
import io.jburrow.server.core.tunnel.application.HandshakeTicket;
import io.jburrow.server.core.tunnel.application.RegisterTunnels;
import io.jburrow.server.core.tunnel.application.RegistrationPolicy;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.jburrow.server.core.tunnel.application.StreamQuota;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.jburrow.server.core.tunnel.domain.Principal;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import io.netty.channel.Channel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The order of the stop ({@code docs/phase-4-staying-connected/ARCHITECTURE.md} §The drain), with a real edge and a
 * real lease keeper over a store that records its writes: readiness first, then the drain and the end of every session,
 * then the flush, which sees every claim as it stands once the sessions are gone. A flush that fails or hangs never
 * holds up the rest.
 */
class EdgeServerLifecycleTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final List<String> steps = new CopyOnWriteArrayList<>();
    private final ExecutorService blocking = Executors.newVirtualThreadPerTaskExecutor();
    private final SubdomainRoutes<Principal, TunnelId> routes = new SubdomainRoutes<>(
            Blocklist.defaults(Set.of()),
            new NameGenerator(new SecureRandom()),
            Duration.ofSeconds(120),
            InstantSource.system());
    private final PortReservationService<Principal> ports = new PortReservationService<>(new PortPool<>(
            PortRange.parse("31990-31999"),
            Duration.ZERO,
            Duration.ZERO,
            Duration.ofSeconds(120),
            new SecureRandom(),
            InstantSource.system()));

    private EdgeServer edge;

    @AfterEach
    void stopEverything() throws Exception {
        if (edge != null) {
            edge.stop(WAIT).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
        blocking.shutdownNow();
    }

    @Test
    void readinessGoesDownFirstAndTheFlushFollowsTheEndOfEverySession() {
        EdgeServerLifecycle lifecycle = lifecycle(store(leases -> steps.add("flush " + leases.size())), null);

        lifecycle.stop();

        assertThat(steps).containsExactly("readiness REFUSING_TRAFFIC", "drain", "end", "flush 1");
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(edge.isAccepting()).isFalse();
        assertThat(edge.stop(WAIT)).isCompleted();
    }

    @Test
    void aFailingFlushDoesntStopTheShutdown() throws Exception {
        EdgeServerLifecycle lifecycle = lifecycle(
                store(leases -> {
                    steps.add("flush");
                    throw new IllegalStateException("Postgres is down");
                }),
                null);

        stopWithin(lifecycle, WAIT);

        assertThat(steps).containsExactly("readiness REFUSING_TRAFFIC", "drain", "end", "flush");
        assertThat(edge.stop(WAIT)).isCompleted();
    }

    @Test
    void aFlushThatHangsIsLeftBehindAfterItsTimeout() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        EdgeServerLifecycle lifecycle = lifecycle(
                store(leases -> {
                    steps.add("flush");
                    try {
                        never.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }),
                Duration.ofMillis(200));
        long started = System.nanoTime();

        stopWithin(lifecycle, WAIT);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(steps).containsExactly("readiness REFUSING_TRAFFIC", "drain", "end", "flush");
        assertThat(edge.stop(WAIT)).isCompleted();
    }

    private static void stopWithin(EdgeServerLifecycle lifecycle, Duration timeout) throws InterruptedException {
        CountDownLatch stopped = new CountDownLatch(1);
        lifecycle.stop(stopped::countDown);
        assertThat(stopped.await(timeout.toMillis(), TimeUnit.MILLISECONDS))
                .as("the stop's callback ran")
                .isTrue();
    }

    /**
     * A started edge and a keeper with one name claimed, so the flush has a lease to write; the keeper's ticker isn't
     * started, so the flush is the only write. {@code flushTimeout} null keeps the default.
     */
    private EdgeServerLifecycle lifecycle(LeaseStore store, Duration flushTimeout) {
        LeaseKeeper<Principal> keeper = new LeaseKeeper<>(
                routes,
                ports,
                store,
                new SilentLeaseEvents(),
                new LeaseKeeper.Settings(UUID.randomUUID(), Duration.ofSeconds(120), Duration.ofSeconds(30)),
                LeaseConfiguration.PRINCIPALS,
                InstantSource.system());
        routes.claim(SubdomainName.parse("quiet-otter").orElseThrow(), new Principal("alice"), TunnelId.random());
        edge = new EdgeServer(settings(), dependencies(), new RecordingSessions());
        edge.start();
        LeaseSchedule schedule =
                flushTimeout == null ? new LeaseSchedule(keeper) : new LeaseSchedule(keeper, flushTimeout);
        ApplicationEventPublisher events = event -> {
            if (event instanceof AvailabilityChangeEvent<?> change && change.getState() instanceof ReadinessState) {
                steps.add("readiness " + change.getState());
            }
        };
        return new EdgeServerLifecycle(edge, schedule, events, Duration.ofSeconds(1));
    }

    private static LeaseStore store(Consumer<List<LeaseRecord>> replace) {
        return new LeaseStore() {
            @Override
            public void replace(UUID instance, List<LeaseRecord> leases) {
                replace.accept(leases);
            }

            @Override
            public List<LeaseRecord> loadUnexpired(Instant now) {
                return List.of();
            }

            @Override
            public int purgeExpiredBefore(Instant cutoff) {
                return 0;
            }
        };
    }

    private EngineDependencies dependencies() {
        SessionAdmission admission = new SessionAdmission(8, 4, 3);
        RegisterTunnels register = new RegisterTunnels(
                token -> Optional.empty(),
                admission,
                ports,
                routes,
                new RegistrationPolicy(1, "localhost", new RegistrationPolicy.HttpPublic("localhost", "http", 80)),
                TunnelMetrics.noop());
        return new EngineDependencies(
                register,
                admission,
                new StreamQuota(8),
                ports,
                routes,
                TunnelMetrics.noop(),
                blocking,
                new TcpSettings("127.0.0.1", "localhost", 32 * 1024));
    }

    private static EdgeSettings settings() {
        return new EdgeSettings(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                "localhost",
                EdgeTransport.NIO,
                2,
                List.of(),
                8,
                4,
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(45),
                64 * 1024,
                256 * 1024,
                new Windows(256 * 1024, 512 * 1024),
                8,
                8,
                "test",
                new HttpSettings(
                        "localhost",
                        1024,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(75),
                        HttpSettings.KERNEL_SIZED,
                        Duration.ofSeconds(1),
                        0,
                        0),
                Set.of(Capability.HTTP, Capability.RESUME));
    }

    /** Sessions that only record that they were drained and ended. */
    private final class RecordingSessions implements SessionInitializer {

        @Override
        public void initialize(Channel channel, String subprotocol, InetAddress clientAddress, HandshakeTicket ticket) {
            channel.close();
        }

        @Override
        public CompletableFuture<Drained> drain(Duration timeout) {
            steps.add("drain");
            return CompletableFuture.completedFuture(Drained.NONE);
        }

        @Override
        public CompletableFuture<Integer> end() {
            steps.add("end");
            return CompletableFuture.completedFuture(0);
        }
    }

    private static final class SilentLeaseEvents implements LeaseEvents {

        @Override
        public void written(int leases, Duration took) {}

        @Override
        public void writeFailed(RuntimeException failure, Duration took, int consecutive) {}
    }
}
