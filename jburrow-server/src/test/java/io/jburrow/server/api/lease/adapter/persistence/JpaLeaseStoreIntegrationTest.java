package io.jburrow.server.api.lease.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.server.core.lease.application.port.LeaseRecord;
import io.jburrow.server.core.lease.application.port.LeaseStore;
import io.jburrow.server.support.TestContainersConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs outside the slice's test transaction, as the keeper does, so each call commits on its own. Every test writes
 * under instance ids of its own and leases that expire in the far future or far past, so tests sharing the container
 * don't see each other's rows as theirs.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({TestContainersConfiguration.class, JpaLeaseStoreIntegrationTest.Store.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JpaLeaseStoreIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private LeaseStore store;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void flywayCreatesTheTableInTheJBurrowSchemaOnTopOfV1() {
        List<String> versions = jdbc.sql(
                        "select version from jburrow.flyway_schema_history where success order by" + " installed_rank")
                .query(String.class)
                .list();
        List<String> schemas = jdbc.sql(
                        "select table_schema from information_schema.tables where table_name = 'tunnel_lease'")
                .query(String.class)
                .list();

        assertThat(versions).containsSubsequence("1", "2");
        assertThat(schemas).containsExactly("jburrow");
    }

    @Test
    void replaceRoundTripsEveryField() {
        UUID instance = UUID.randomUUID();
        List<LeaseRecord> leases = List.of(
                name("quiet-otter-" + instance.toString().substring(0, 8), "alice", true, NOW.plusSeconds(150)),
                port(20_417, "bob", false, NOW.plusSeconds(120)));

        store.replace(instance, leases);

        assertThat(rowsOf(instance)).containsExactlyInAnyOrderElementsOf(leases);
    }

    @Test
    void replaceSwapsOnlyItsOwnInstancesRows() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        LeaseRecord theirLease = port(20_001, "bob", true, NOW.plusSeconds(150));
        store.replace(theirs, List.of(theirLease));
        store.replace(mine, List.of(port(20_001, "alice", true, NOW.plusSeconds(150))));

        LeaseRecord replacement = port(20_002, "alice", false, NOW.plusSeconds(100));
        store.replace(mine, List.of(replacement));

        assertThat(rowsOf(mine)).containsExactly(replacement);
        assertThat(rowsOf(theirs)).containsExactly(theirLease);

        store.replace(mine, List.of());
        assertThat(rowsOf(mine)).isEmpty();
    }

    @Test
    void aFailedReplaceLeavesThePreviousSnapshot() {
        UUID instance = UUID.randomUUID();
        LeaseRecord kept = port(20_003, "alice", true, NOW.plusSeconds(150));
        store.replace(instance, List.of(kept));

        LeaseRecord duplicate = port(20_004, "alice", true, NOW.plusSeconds(150));
        assertThatThrownBy(() -> store.replace(instance, List.of(duplicate, duplicate)))
                .isInstanceOf(RuntimeException.class);

        assertThat(rowsOf(instance)).containsExactly(kept);
    }

    @Test
    void loadUnexpiredReadsEveryInstanceButNotWhatHasExpired() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        LeaseRecord counting = port(20_005, "alice", false, NOW.plusSeconds(60));
        LeaseRecord alsoCounting = port(20_005, "alice", true, NOW.plusSeconds(90));
        LeaseRecord expired = port(20_006, "alice", false, NOW.minusSeconds(1));
        store.replace(first, List.of(counting, expired));
        store.replace(second, List.of(alsoCounting));

        assertThat(store.loadUnexpired(NOW)).contains(counting, alsoCounting).doesNotContain(expired);
    }

    @Test
    void purgeDeletesOnlyRowsExpiredBeforeTheCutoff() {
        UUID instance = UUID.randomUUID();
        Instant longAgo = Instant.parse("2001-01-01T00:00:00Z");
        LeaseRecord ancient = port(20_007, "alice", false, longAgo.minusSeconds(1));
        LeaseRecord recent = port(20_008, "alice", false, longAgo.plusSeconds(1));
        store.replace(instance, List.of(ancient, recent));

        int purged = store.purgeExpiredBefore(longAgo);

        assertThat(purged).isEqualTo(1);
        assertThat(rowsOf(instance)).containsExactly(recent);
    }

    @Test
    void theKindColumnRefusesAnythingButNameAndPort() {
        assertThatThrownBy(() -> jdbc.sql(
                                "insert into jburrow.tunnel_lease values (?, 'host', 'x', 'alice', true," + " now())")
                        .param(UUID.randomUUID())
                        .update())
                .isInstanceOf(RuntimeException.class);
    }

    private List<LeaseRecord> rowsOf(UUID instance) {
        return jdbc.sql("select kind, lease_key, principal, active, expires_at from jburrow.tunnel_lease where"
                        + " instance_id = ?")
                .param(instance)
                .query((row, number) -> new LeaseRecord(
                        LeaseEntity.kindOf(row.getString("kind")),
                        row.getString("lease_key"),
                        row.getString("principal"),
                        row.getBoolean("active"),
                        row.getTimestamp("expires_at").toInstant()))
                .list();
    }

    private static LeaseRecord name(String name, String principal, boolean active, Instant expiresAt) {
        return new LeaseRecord(LeaseRecord.Kind.NAME, name, principal, active, expiresAt);
    }

    private static LeaseRecord port(int port, String principal, boolean active, Instant expiresAt) {
        return new LeaseRecord(LeaseRecord.Kind.PORT, Integer.toString(port), principal, active, expiresAt);
    }

    /** The store as the composition root builds it, and the composition root's entity scan, which a slice skips. */
    @TestConfiguration(proxyBeanMethods = false)
    @EntityScan(basePackages = "io.jburrow.server.api")
    static class Store {

        @Bean
        LeaseStore leaseStore(LeaseJpaRepository repository) {
            return new JpaLeaseStore(repository);
        }
    }
}
