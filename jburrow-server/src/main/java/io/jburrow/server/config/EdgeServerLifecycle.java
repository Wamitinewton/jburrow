package io.jburrow.server.config;

import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;

/**
 * Ties the {@link EdgeServer} to the application's lifecycle: it starts after the web server, so tunnels are only
 * accepted once everything else is up, and stops before the web server's graceful shutdown begins. The leases of
 * earlier processes are seeded as holds before the edge binds, and the lease keeper ticks while it runs.
 *
 * <p>Stops in the order of {@code docs/phase-4-staying-connected/ARCHITECTURE.md} §The drain: readiness down, the
 * edge's drain, which ends every session and so puts every claim on hold, the final lease flush, then the event loops.
 * Each step waits a bounded time and the stop goes on whatever it found, so the whole stop fits the shutdown phase.
 */
final class EdgeServerLifecycle implements SmartLifecycle {

    /**
     * Above the web server's start-stop phase ({@link WebServerApplicationContext#START_STOP_LIFECYCLE_PHASE}) and its
     * graceful shutdown phase ({@link WebServerApplicationContext#GRACEFUL_SHUTDOWN_PHASE}): later phases start later
     * and stop earlier.
     */
    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 512;

    /** Room for each bounded wait's own scheduling, so a step that ends on time isn't taken for one that didn't. */
    private static final Duration SLACK = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(EdgeServerLifecycle.class);

    private final EdgeServer edge;
    private final LeaseSchedule leases;
    private final ApplicationEventPublisher events;
    private final Duration drainTimeout;
    private volatile boolean running;

    EdgeServerLifecycle(
            EdgeServer edge, LeaseSchedule leases, ApplicationEventPublisher events, Duration drainTimeout) {
        this.edge = Objects.requireNonNull(edge, "edge");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.events = Objects.requireNonNull(events, "events");
        this.drainTimeout = Objects.requireNonNull(drainTimeout, "drainTimeout");
    }

    /**
     * The longest the stop takes with a drain of {@code drainTimeout}: the drain, the sessions' closing, the final
     * flush and the event loops' shutdown. It has to fit {@code spring.lifecycle.timeout-per-shutdown-phase}.
     */
    static Duration worstCaseStop(Duration drainTimeout) {
        return drainTimeout
                .plus(EdgeServer.CLOSE_TIMEOUT)
                .plus(EdgeServer.RELEASE_GRACE)
                .plus(LeaseSchedule.FLUSH_TIMEOUT)
                .plus(EdgeServer.GROUP_SHUTDOWN_TIMEOUT);
    }

    /** Flyway has run by now: every singleton, the data source included, is initialized before any lifecycle starts. */
    @Override
    public void start() {
        leases.seed();
        edge.start();
        leases.start();
        running = true;
    }

    /**
     * Readiness goes down on the caller's thread, before anything else; the rest runs on a virtual thread of its own,
     * since it waits on the drain and on Postgres.
     */
    @Override
    public void stop(Runnable callback) {
        running = false;
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        Thread.ofVirtual().name("jburrow-edge-stop").start(() -> {
            try {
                drainFlushAndStop();
            } catch (RuntimeException e) {
                log.error("The edge did not stop cleanly", e);
            } finally {
                callback.run();
            }
        });
    }

    private void drainFlushAndStop() {
        await(
                edge.drain(),
                drainTimeout
                        .plus(EdgeServer.CLOSE_TIMEOUT)
                        .plus(EdgeServer.RELEASE_GRACE)
                        .plus(SLACK),
                "drain");
        leases.flush();
        // Nothing ticks after the flush, so the next process seeds what the flush wrote.
        leases.stop();
        await(
                edge.stop(EdgeServer.GROUP_SHUTDOWN_TIMEOUT.plus(SLACK)),
                EdgeServer.GROUP_SHUTDOWN_TIMEOUT.plus(SLACK),
                "stop");
    }

    private static void await(CompletableFuture<Void> step, Duration timeout, String name) {
        try {
            step.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            log.warn("The edge's {} didn't finish within {} ms; going on", name, timeout.toMillis());
        } catch (ExecutionException e) {
            log.warn("The edge's {} failed; going on", name, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        CountDownLatch stopped = new CountDownLatch(1);
        stop(stopped::countDown);
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
