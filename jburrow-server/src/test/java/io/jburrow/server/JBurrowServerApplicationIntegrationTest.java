package io.jburrow.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.support.TestContainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestContainersConfiguration.class)
class JBurrowServerApplicationIntegrationTest {

    @LocalServerPort
    private int apiPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private InstanceId currentInstanceId;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void startupRecordsExactlyOneRowForThisInstance() {
        long rows = jdbc.sql("select count(*) from jburrow.server_instance where instance_id = ?")
                .param(currentInstanceId.value())
                .query(Long.class)
                .single();

        assertThat(rows).isOne();
    }

    @Test
    void metaReturnsTheRecordedInstance() {
        String startedAt = jdbc.sql("select to_char(started_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS') from"
                        + " jburrow.server_instance where instance_id = ?")
                .param(currentInstanceId.value())
                .query(String.class)
                .single();

        JsonNode body = client(apiPort).get().uri("/api/v1/meta").retrieve().body(JsonNode.class);

        assertThat(body.get("success").asBoolean()).isTrue();
        JsonNode data = body.get("data");
        assertThat(data.get("instanceId").asString()).isEqualTo(currentInstanceId.toString());
        assertThat(data.get("version").asString()).isNotBlank();
        assertThat(data.get("commit").asString()).isNotBlank();
        assertThat(data.get("startedAt").asString()).startsWith(startedAt);
        assertThat(data.has("profiles")).isFalse();
    }

    @Test
    void managementPortReportsHealthUp() {
        JsonNode health =
                client(managementPort).get().uri("/actuator/health").retrieve().body(JsonNode.class);

        assertThat(health.get("status").asString()).isEqualTo("UP");
        assertThat(health.at("/components/db/status").asString()).isEqualTo("UP");
        assertThat(health.at("/components/mail/status").asString()).isEqualTo("UP");
    }

    @Test
    void apiPortDoesNotServeActuator() {
        ResponseEntity<Void> response = client(apiPort)
                .get()
                .uri("/actuator/health")
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, reply) -> {})
                .toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    private static RestClient client(int port) {
        return RestClient.create("http://localhost:" + port);
    }
}
