package io.jburrow.server.api.lease.adapter.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaseJpaRepository extends JpaRepository<LeaseEntity, LeaseEntity.Key> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from LeaseEntity lease where lease.id.instanceId = :instance")
    int deleteByInstance(@Param("instance") UUID instance);

    @Query("select lease from LeaseEntity lease where lease.expiresAt > :now")
    List<LeaseEntity> findUnexpired(@Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from LeaseEntity lease where lease.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
