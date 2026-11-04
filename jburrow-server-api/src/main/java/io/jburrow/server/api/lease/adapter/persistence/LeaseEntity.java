package io.jburrow.server.api.lease.adapter.persistence;

import io.jburrow.server.core.lease.application.port.LeaseRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A row of {@code jburrow.tunnel_lease}. Always new when saved: a write deletes the instance's rows first, so an insert
 * needs no select to find out.
 */
@Entity
@Table(name = "tunnel_lease")
public class LeaseEntity implements Persistable<LeaseEntity.Key> {

    @EmbeddedId
    private Key id;

    @Column(name = "principal", nullable = false)
    private String principal;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Transient
    private boolean persisted;

    protected LeaseEntity() {
        persisted = true;
    }

    private LeaseEntity(Key id, String principal, boolean active, Instant expiresAt) {
        this.id = id;
        this.principal = principal;
        this.active = active;
        this.expiresAt = expiresAt;
    }

    public static LeaseEntity fromRecord(UUID instance, LeaseRecord lease) {
        return new LeaseEntity(
                new Key(instance, kindColumn(lease.kind()), lease.key()),
                lease.principal(),
                lease.active(),
                lease.expiresAt());
    }

    public LeaseRecord toRecord() {
        return new LeaseRecord(kindOf(id.kind), id.leaseKey, principal, active, expiresAt);
    }

    @Override
    public Key getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    static String kindColumn(LeaseRecord.Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    static LeaseRecord.Kind kindOf(String column) {
        return LeaseRecord.Kind.valueOf(column.toUpperCase(Locale.ROOT));
    }

    /** The primary key: one row per instance, kind and key. */
    @Embeddable
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "instance_id", nullable = false)
        private UUID instanceId;

        @Column(name = "kind", nullable = false)
        private String kind;

        @Column(name = "lease_key", nullable = false)
        private String leaseKey;

        protected Key() {}

        Key(UUID instanceId, String kind, String leaseKey) {
            this.instanceId = instanceId;
            this.kind = kind;
            this.leaseKey = leaseKey;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && instanceId.equals(key.instanceId)
                    && kind.equals(key.kind)
                    && leaseKey.equals(key.leaseKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(instanceId, kind, leaseKey);
        }
    }
}
