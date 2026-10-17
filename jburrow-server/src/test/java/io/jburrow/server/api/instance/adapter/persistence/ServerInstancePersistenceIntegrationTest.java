package io.jburrow.server.api.instance.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.api.instance.domain.BuildIdentity;
import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.api.instance.domain.ServerInstance;
import io.jburrow.server.support.TestContainersConfiguration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TestContainersConfiguration.class)
class ServerInstancePersistenceIntegrationTest {

    @Autowired
    private ServerInstanceJpaRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void flywayCreatesTheTableInTheJBurrowSchema() {
        List<String> schemas = jdbc.sql("""
                        select table_schema from information_schema.tables where table_name = 'server_instance'
                        """).query(String.class).list();

        assertThat(schemas).containsExactly("jburrow");
    }

    @Test
    void saveAndFindRoundTripEveryField() {
        JpaServerInstanceStore store = new JpaServerInstanceStore(repository);
        ServerInstance instance = new ServerInstance(
                InstanceId.random(),
                new BuildIdentity("0.1.0", "abc1234", List.of("prod", "kubernetes")),
                Instant.parse("2026-10-17T08:00:00.123456Z"));

        store.save(instance);
        entityManager.flush();
        entityManager.clear();

        assertThat(store.find(instance.id())).contains(instance);
        assertThat(store.find(InstanceId.random())).isEmpty();
    }

    /** Mirrors the composition root's entity scan, which a JPA slice does not load. */
    @TestConfiguration(proxyBeanMethods = false)
    @EntityScan(basePackages = "io.jburrow.server.api")
    static class EntityScanning {}
}
