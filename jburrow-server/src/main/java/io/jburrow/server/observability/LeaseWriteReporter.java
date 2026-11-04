package io.jburrow.server.observability;

import io.jburrow.server.core.lease.application.port.LeaseEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LeaseEvents} as {@code jburrow_lease_writes_total{outcome}}, {@code jburrow_lease_write_seconds} and a
 * "lease write failed" warning at most once a minute while writes keep failing.
 */
public final class LeaseWriteReporter implements LeaseEvents {

    static final Duration WARN_INTERVAL = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(LeaseWriteReporter.class);

    private final Counter ok;
    private final Counter failed;
    private final Timer duration;
    private final InstantSource clock;
    private Instant lastWarning;

    public LeaseWriteReporter(MeterRegistry registry, InstantSource clock) {
        Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ok = writes(registry, "ok");
        this.failed = writes(registry, "failed");
        this.duration = Timer.builder("jburrow.lease.write")
                .description("Time taken by lease writes, failed ones included")
                .register(registry);
    }

    @Override
    public void written(int leases, Duration took) {
        ok.increment();
        duration.record(took);
        if (lastWarning != null) {
            log.info("lease write recovered: {} leases written", leases);
            lastWarning = null;
        }
    }

    /** Called by one writer at a time, so the warning's timestamp needs no more than the keeper's own lock. */
    @Override
    public void writeFailed(RuntimeException failure, Duration took, int consecutive) {
        failed.increment();
        duration.record(took);
        Instant now = clock.instant();
        if (lastWarning == null || !now.isBefore(lastWarning.plus(WARN_INTERVAL))) {
            lastWarning = now;
            log.warn("lease write failed: {} in a row; retrying every tick", consecutive, failure);
        }
    }

    private static Counter writes(MeterRegistry registry, String outcome) {
        return Counter.builder("jburrow.lease.writes")
                .description("Lease snapshots written to Postgres, by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }
}
