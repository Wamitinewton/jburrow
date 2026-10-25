package io.jburrow.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.support.MailpitContainer;
import io.jburrow.server.support.TestContainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Stops this context's Mailpit, so the context is discarded afterwards rather than handed to another test class. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestContainersConfiguration.class)
@DirtiesContext
class HealthGroupsIntegrationTest {

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private MailpitContainer mailpit;

    @Test
    void livenessContainsOnlyLivenessState() {
        JsonNode liveness = health("/liveness").getBody();

        assertThat(liveness.get("components").propertyNames()).containsExactly("livenessState");
    }

    @Test
    void readinessContainsOnlyReadinessState() {
        JsonNode readiness = health("/readiness").getBody();

        assertThat(readiness.get("components").propertyNames()).containsExactly("readinessState");
    }

    @Test
    void fullHealthIncludesTheDependencies() {
        JsonNode all = health("").getBody();

        assertThat(all.get("components").propertyNames()).contains("db", "mail", "diskSpace", "edge");
    }

    @Test
    void mailOutageLeavesReadinessUp() {
        assertThat(health("").getBody().at("/components/mail/status").asString())
                .isEqualTo("UP");

        mailpit.stop();

        ResponseEntity<JsonNode> all = health("");
        assertThat(all.getBody().at("/components/mail/status").asString()).isEqualTo("DOWN");
        ResponseEntity<JsonNode> readiness = health("/readiness");
        assertThat(readiness.getStatusCode().value()).isEqualTo(200);
        assertThat(readiness.getBody().get("status").asString()).isEqualTo("UP");
    }

    private ResponseEntity<JsonNode> health(String group) {
        return RestClient.create("http://localhost:" + managementPort)
                .get()
                .uri("/actuator/health" + group)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, reply) -> {})
                .toEntity(JsonNode.class);
    }
}
