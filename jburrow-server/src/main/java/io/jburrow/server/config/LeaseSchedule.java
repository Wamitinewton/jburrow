package io.jburrow.server.config;

import io.jburrow.server.core.lease.application.LeaseKeeper;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the {@link LeaseKeeper}: seeds the holds once, then ticks it every second on one virtual thread, so a write in
 * progress delays the next tick rather than overlapping it, and flushes it once the drain has ended every session.
 */
final class LeaseSchedule {

    static final Duration TICK = Duration.ofSeconds(1);

    /** How long stopping waits for a write in flight before interrupting it. */
    static final Duration STOP_TIMEOUT = Duration.ofSeconds(2);

    /** How long the final flush may take before the stop goes on without it. */
    static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(2);

    private static final Logger log = LoggerFactory.getLogger(LeaseSchedule.class);

    private final LeaseKeeper<?> keeper;
    private final Duration flushTimeout;
    private ScheduledExecutorService ticker;

    LeaseSchedule(LeaseKeeper<?> keeper) {
        this(keeper, FLUSH_TIMEOUT);
    }

    LeaseSchedule(LeaseKeeper<?> keeper, Duration flushTimeout) {
        this.keeper = Objects.requireNonNull(keeper, "keeper");
        this.flushTimeout = Objects.requireNonNull(flushTimeout, "flushTimeout");
    }

    /** Seeds the holds the leases of earlier processes still count for; a failure stops startup. */
    void seed() {
        LeaseKeeper.Seeded seeded = keeper.seed();
        log.info(
                "leases seeded: {}, {}; oldest expiry {}, newest {}; {} dropped, {} purged",
                plural(seeded.names(), "name"),
                plural(seeded.ports(), "port"),
                seeded.oldestExpiry().map(Object::toString).orElse("none"),
                seeded.newestExpiry().map(Object::toString).orElse("none"),
                seeded.dropped(),
                seeded.purged());
    }

    synchronized void start() {
        if (ticker != null) {
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("jburrow-lease-keeper").factory());
        ticker.scheduleWithFixedDelay(this::tick, TICK.toMillis(), TICK.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stops ticking, waiting up to {@link #STOP_TIMEOUT} for a write in flight, so none outlives the data source. */
    synchronized void stop() {
        if (ticker == null) {
            return;
        }
        ticker.shutdown();
        try {
            if (!ticker.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                ticker.shutdownNow();
                log.warn("A lease write was still running {} after the stop began; interrupted it", STOP_TIMEOUT);
            }
        } catch (InterruptedException e) {
            ticker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        ticker = null;
    }

    /**
     * Writes the final snapshot, step 8 of {@code docs/phase-4-staying-connected/ARCHITECTURE.md} §The drain, on a
     * virtual thread of its own, waiting at most the flush timeout. Never throws: if the flush fails, the last periodic
     * write still covers every claim older than {@code jburrow.lease.refresh}.
     *
     * @return whether the store holds this process's claims as they are now
     */
    boolean flush() {
        FutureTask<Boolean> flush = new FutureTask<>(keeper::flush);
        Thread writer = Thread.ofVirtual().name("jburrow-lease-flush").start(flush);
        try {
            if (flush.get(flushTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                return true;
            }
            log.warn("The final lease flush failed; the next process seeds the last periodic write");
        } catch (TimeoutException e) {
            writer.interrupt();
            log.warn(
                    "The final lease flush took longer than {} ms; the next process seeds the last periodic write",
                    flushTimeout.toMillis());
        } catch (ExecutionException e) {
            log.warn("The final lease flush failed; the next process seeds the last periodic write", e.getCause());
        } catch (InterruptedException e) {
            writer.interrupt();
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /** A task that throws is never run again, so nothing may escape. */
    private void tick() {
        try {
            keeper.tick();
        } catch (RuntimeException e) {
            log.error("The lease keeper's tick failed", e);
        }
    }

    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
