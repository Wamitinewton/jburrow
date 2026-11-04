package io.jburrow.server.api.lease.adapter.persistence;

import io.jburrow.server.core.lease.application.port.LeaseRecord;
import io.jburrow.server.core.lease.application.port.LeaseStore;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** {@link LeaseStore} on the {@code jburrow.tunnel_lease} table. */
public class JpaLeaseStore implements LeaseStore {

    private final LeaseJpaRepository repository;

    public JpaLeaseStore(LeaseJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /** One delete and one batch of inserts, so a reader sees the old snapshot or the new one, never a mix. */
    @Override
    @Transactional
    public void replace(UUID instance, List<LeaseRecord> leases) {
        Objects.requireNonNull(instance, "instance");
        repository.deleteByInstance(instance);
        repository.saveAll(leases.stream()
                .map(lease -> LeaseEntity.fromRecord(instance, lease))
                .toList());
        repository.flush();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LeaseRecord> loadUnexpired(Instant now) {
        return repository.findUnexpired(now).stream().map(LeaseEntity::toRecord).toList();
    }

    @Override
    @Transactional
    public int purgeExpiredBefore(Instant cutoff) {
        return repository.deleteExpiredBefore(cutoff);
    }
}
