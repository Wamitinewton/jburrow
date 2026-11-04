package io.jburrow.server.core.lease.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A set of leases with at most one per name and one per port. */
public record LeaseSnapshot(List<Lease> leases) {

    public static final LeaseSnapshot EMPTY = new LeaseSnapshot(List.of());

    public LeaseSnapshot {
        leases = List.copyOf(leases);
        if (leases.stream().map(LeaseSnapshot::keyOf).distinct().count() != leases.size()) {
            throw new IllegalArgumentException("A lease snapshot holds one lease per name and per port");
        }
    }

    /**
     * The rows of every process that still count at {@code now}, one per key: where two processes wrote the same key,
     * the one with the latest {@code expiresAt} wins.
     */
    public static LeaseSnapshot latestPerKey(Collection<Lease> rows, Instant now) {
        Objects.requireNonNull(now, "now");
        Map<Key, Lease> latest = new LinkedHashMap<>();
        for (Lease row : rows) {
            if (!row.expiredAt(now)) {
                latest.merge(
                        keyOf(row),
                        row,
                        (kept, candidate) -> candidate.expiresAt().isAfter(kept.expiresAt()) ? candidate : kept);
            }
        }
        return new LeaseSnapshot(List.copyOf(latest.values()));
    }

    public boolean isEmpty() {
        return leases.isEmpty();
    }

    public int count(LeaseKind kind) {
        return (int) leases.stream().filter(lease -> lease.kind() == kind).count();
    }

    public Optional<Instant> oldestExpiry() {
        return leases.stream().map(Lease::expiresAt).min(Comparator.naturalOrder());
    }

    public Optional<Instant> newestExpiry() {
        return leases.stream().map(Lease::expiresAt).max(Comparator.naturalOrder());
    }

    private static Key keyOf(Lease lease) {
        return new Key(lease.kind(), lease.key());
    }

    private record Key(LeaseKind kind, String key) {}
}
