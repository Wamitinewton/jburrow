package io.jburrow.server.core.lease.application;

import io.jburrow.server.core.lease.application.port.LeaseRecord;
import io.jburrow.server.core.lease.application.port.LeaseStore;
import io.jburrow.server.core.lease.domain.Lease;
import io.jburrow.server.core.lease.domain.LeaseKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A {@link LeaseStore} in a map, which counts its calls and can be told to fail. Tests read and write it in domain
 * {@link Lease}s.
 */
final class InMemoryLeaseStore implements LeaseStore {

    private final Map<UUID, List<LeaseRecord>> rows = new LinkedHashMap<>();
    int replaces;
    int queries;
    RuntimeException failure;

    @Override
    public void replace(UUID instance, List<LeaseRecord> leases) {
        queries++;
        failIfTold();
        replaces++;
        rows.put(instance, List.copyOf(leases));
    }

    @Override
    public List<LeaseRecord> loadUnexpired(Instant now) {
        queries++;
        failIfTold();
        List<LeaseRecord> unexpired = new ArrayList<>();
        rows.values()
                .forEach(leases -> leases.stream()
                        .filter(lease -> lease.expiresAt().isAfter(now))
                        .forEach(unexpired::add));
        return unexpired;
    }

    @Override
    public int purgeExpiredBefore(Instant cutoff) {
        queries++;
        failIfTold();
        int[] purged = {0};
        rows.replaceAll((instance, leases) -> {
            List<LeaseRecord> kept = leases.stream()
                    .filter(lease -> !lease.expiresAt().isBefore(cutoff))
                    .toList();
            purged[0] += leases.size() - kept.size();
            return kept;
        });
        return purged[0];
    }

    /** Writes {@code leases} as {@code instance}'s rows, as an earlier process would have. */
    void put(UUID instance, List<Lease> leases) {
        rows.put(instance, leases.stream().map(InMemoryLeaseStore::toRecord).toList());
    }

    /** Writes rows the domain would refuse, as a damaged table might hold. */
    void putRecords(UUID instance, List<LeaseRecord> records) {
        rows.put(instance, List.copyOf(records));
    }

    List<Lease> of(UUID instance) {
        return rows.getOrDefault(instance, List.of()).stream()
                .map(InMemoryLeaseStore::toDomain)
                .toList();
    }

    private void failIfTold() {
        if (failure != null) {
            throw failure;
        }
    }

    private static LeaseRecord toRecord(Lease lease) {
        return new LeaseRecord(
                LeaseRecord.Kind.valueOf(lease.kind().name()),
                lease.key(),
                lease.principal(),
                lease.active(),
                lease.expiresAt());
    }

    private static Lease toDomain(LeaseRecord record) {
        return new Lease(
                LeaseKind.valueOf(record.kind().name()),
                record.key(),
                record.principal(),
                record.active(),
                record.expiresAt());
    }
}
