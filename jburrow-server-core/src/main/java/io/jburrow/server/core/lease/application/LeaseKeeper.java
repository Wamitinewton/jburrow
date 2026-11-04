package io.jburrow.server.core.lease.application;

import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.server.core.lease.application.port.LeaseEvents;
import io.jburrow.server.core.lease.application.port.LeaseRecord;
import io.jburrow.server.core.lease.application.port.LeaseStore;
import io.jburrow.server.core.lease.domain.Lease;
import io.jburrow.server.core.lease.domain.LeaseKind;
import io.jburrow.server.core.lease.domain.LeaseSnapshot;
import io.jburrow.server.core.portpool.application.port.PortClaimSnapshot;
import io.jburrow.server.core.portpool.application.port.PortHolds;
import io.jburrow.server.core.routing.application.port.ClaimSnapshot;
import io.jburrow.server.core.routing.application.port.ClaimSnapshots;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Carries this process's names and ports across a restart: writes a snapshot of both tables behind the byte path, and
 * at startup seeds the snapshots every process left as holds (ADR-0015).
 *
 * <p>The keeper reads the tables' change counters rather than being told about claims, so nothing on the registration
 * path knows it exists. It never runs on an event loop: {@link #tick()} and {@link #flush()} block on the store.
 *
 * @param <P> who a claim is for, turned into a lease's principal string and back by the composition root
 */
public final class LeaseKeeper<P> {

    /** How long a row that has stopped counting is kept before {@link #seed()} deletes it. */
    public static final Duration PURGE_AFTER = Duration.ofDays(1);

    private final ClaimSnapshots<P> names;
    private final PortHolds<P> ports;
    private final LeaseStore store;
    private final LeaseEvents events;
    private final Settings settings;
    private final InstantSource clock;
    private final Principals<P> principals;

    private final ReentrantLock writing = new ReentrantLock();
    private long writtenNamesVersion;
    private long writtenPortsVersion;
    private Instant lastWrite;
    private boolean lastWriteEmpty = true;
    private int consecutiveFailures;

    public LeaseKeeper(
            ClaimSnapshots<P> names,
            PortHolds<P> ports,
            LeaseStore store,
            LeaseEvents events,
            Settings settings,
            Principals<P> principals,
            InstantSource clock) {
        this.names = Objects.requireNonNull(names, "names");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.store = Objects.requireNonNull(store, "store");
        this.events = Objects.requireNonNull(events, "events");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.writtenNamesVersion = names.version();
        this.writtenPortsVersion = ports.version();
        this.lastWrite = clock.instant();
    }

    /**
     * Deletes rows that stopped counting more than {@link #PURGE_AFTER} ago, then holds every key a row of any process
     * still counts for, until the latest {@code expiresAt} written for it. An active row becomes a hold too: the
     * process that wrote it is gone, and so is its session. Runs once, before the edge accepts a connection.
     *
     * @throws RuntimeException whatever the store throws, so startup fails as it does when Flyway can't connect
     */
    public Seeded seed() {
        Instant now = clock.instant();
        int purged = store.purgeExpiredBefore(now.minus(PURGE_AFTER));
        List<LeaseRecord> records = store.loadUnexpired(now);
        List<Lease> readable = new ArrayList<>(records.size());
        for (LeaseRecord record : records) {
            fromRecord(record).ifPresent(readable::add);
        }
        LeaseSnapshot rows = LeaseSnapshot.latestPerKey(readable, now);
        List<Lease> seeded = new ArrayList<>(rows.leases().size());
        for (Lease row : rows.leases()) {
            if (seedHold(row)) {
                seeded.add(row);
            }
        }
        LeaseSnapshot held = new LeaseSnapshot(seeded);
        return new Seeded(
                held.count(LeaseKind.NAME),
                held.count(LeaseKind.PORT),
                rows.leases().size() - seeded.size() + records.size() - readable.size(),
                purged,
                held.oldestExpiry(),
                held.newestExpiry());
    }

    /**
     * Writes a fresh snapshot when either table changed since the last successful write, or when the refresh interval
     * has passed and there is something to keep. Never throws for a failed write: it is reported, and the next tick
     * tries again.
     */
    public void tick() {
        write(false);
    }

    /**
     * Writes a fresh snapshot now, unless this process has nothing to keep and nothing of it is written.
     *
     * @return whether the store holds this process's current claims
     */
    public boolean flush() {
        return write(true);
    }

    private boolean write(boolean forced) {
        writing.lock();
        try {
            Instant now = clock.instant();
            long namesVersion = names.version();
            long portsVersion = ports.version();
            boolean changed = namesVersion != writtenNamesVersion || portsVersion != writtenPortsVersion;
            boolean refreshDue = !now.isBefore(lastWrite.plus(settings.refresh()));
            if (!forced && !changed && !refreshDue) {
                return true;
            }
            List<Lease> leases = leasesAt(now);
            if (leases.isEmpty() && lastWriteEmpty) {
                markWritten(namesVersion, portsVersion, now, true);
                return true;
            }
            try {
                store.replace(
                        settings.instance(),
                        leases.stream().map(LeaseKeeper::toRecord).toList());
            } catch (RuntimeException failure) {
                consecutiveFailures++;
                events.writeFailed(failure, Duration.between(now, clock.instant()), consecutiveFailures);
                return false;
            }
            markWritten(namesVersion, portsVersion, now, leases.isEmpty());
            events.written(leases.size(), Duration.between(now, clock.instant()));
            return true;
        } finally {
            writing.unlock();
        }
    }

    private void markWritten(long namesVersion, long portsVersion, Instant at, boolean empty) {
        writtenNamesVersion = namesVersion;
        writtenPortsVersion = portsVersion;
        lastWrite = at;
        lastWriteEmpty = empty;
        consecutiveFailures = 0;
    }

    /** An active claim counts until one missed refresh and a whole window later; a hold, until it ends. */
    private List<Lease> leasesAt(Instant now) {
        Instant activeUntil = now.plus(settings.refresh()).plus(settings.resumeWindow());
        List<Lease> leases = new ArrayList<>();
        for (ClaimSnapshot<P> claim : names.snapshot(now)) {
            leases.add(Lease.name(
                    claim.name().value(),
                    principals.toLease(claim.principal()),
                    claim.active(),
                    claim.active() ? activeUntil : claim.heldUntil()));
        }
        for (PortClaimSnapshot<P> claim : ports.snapshot(now)) {
            leases.add(Lease.port(
                    claim.port(),
                    principals.toLease(claim.principal()),
                    claim.active(),
                    claim.active() ? activeUntil : claim.heldUntil()));
        }
        return leases;
    }

    private boolean seedHold(Lease row) {
        Optional<P> principal = principals.fromLease(row.principal());
        if (principal.isEmpty()) {
            return false;
        }
        return switch (row.kind()) {
            case NAME ->
                SubdomainName.parse(row.key())
                        .map(name -> names.seedHold(name, principal.get(), row.expiresAt()))
                        .orElse(false);
            case PORT ->
                parsePort(row.key())
                        .map(port -> ports.seedHold(port, principal.get(), row.expiresAt()))
                        .orElse(false);
        };
    }

    private static LeaseRecord toRecord(Lease lease) {
        LeaseRecord.Kind kind =
                switch (lease.kind()) {
                    case NAME -> LeaseRecord.Kind.NAME;
                    case PORT -> LeaseRecord.Kind.PORT;
                };
        return new LeaseRecord(kind, lease.key(), lease.principal(), lease.active(), lease.expiresAt());
    }

    /** Empty for a record the domain refuses, which seeding drops like any other row it can't hold. */
    private static Optional<Lease> fromRecord(LeaseRecord record) {
        LeaseKind kind =
                switch (record.kind()) {
                    case NAME -> LeaseKind.NAME;
                    case PORT -> LeaseKind.PORT;
                };
        try {
            return Optional.of(new Lease(kind, record.key(), record.principal(), record.active(), record.expiresAt()));
        } catch (IllegalArgumentException refused) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> parsePort(String key) {
        try {
            int port = Integer.parseInt(key);
            return port >= 1 && port <= Lease.MAX_PORT ? Optional.of(port) : Optional.empty();
        } catch (NumberFormatException notAPort) {
            return Optional.empty();
        }
    }

    /**
     * @param instance this process's instance id, which every row it writes carries
     * @param resumeWindow how long a released claim is held, which an active claim's lease adds to its refresh
     * @param refresh the longest gap between writes while there are leases to keep
     */
    public record Settings(UUID instance, Duration resumeWindow, Duration refresh) {

        public Settings {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(resumeWindow, "resumeWindow");
            Objects.requireNonNull(refresh, "refresh");
            if (resumeWindow.isNegative()) {
                throw new IllegalArgumentException("The resume window must not be negative");
            }
            if (refresh.isNegative() || refresh.isZero()) {
                throw new IllegalArgumentException("The lease refresh must be positive");
            }
        }
    }

    /** How a principal becomes a lease's string and back; a string that names no principal is dropped at seeding. */
    public record Principals<P>(Function<P, String> toLease, Function<String, Optional<P>> fromLease) {

        public Principals {
            Objects.requireNonNull(toLease, "toLease");
            Objects.requireNonNull(fromLease, "fromLease");
        }

        String toLease(P principal) {
            return toLease.apply(principal);
        }

        Optional<P> fromLease(String principal) {
            return fromLease.apply(principal);
        }
    }

    /**
     * What {@link #seed()} did, for the "leases seeded" log line.
     *
     * @param dropped rows that still counted but couldn't become a hold: a port outside the range, a key already
     *     claimed, or a key or principal that doesn't parse; a key written twice counts once
     * @param purged rows deleted for having stopped counting more than {@link #PURGE_AFTER} ago
     */
    public record Seeded(
            int names,
            int ports,
            int dropped,
            int purged,
            Optional<Instant> oldestExpiry,
            Optional<Instant> newestExpiry) {}
}
