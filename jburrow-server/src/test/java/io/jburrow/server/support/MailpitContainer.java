package io.jburrow.server.support;

import java.util.List;
import java.util.Optional;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/** Mailpit: an SMTP server that keeps what it receives and serves it back over its HTTP API. */
public class MailpitContainer extends GenericContainer<MailpitContainer> {

    public static final int SMTP_PORT = 1025;
    public static final int HTTP_PORT = 8025;

    public MailpitContainer(String image) {
        super(DockerImageName.parse(image));
        withExposedPorts(SMTP_PORT, HTTP_PORT);
        waitingFor(Wait.forHttp("/livez").forPort(HTTP_PORT));
    }

    public int smtpPort() {
        return getMappedPort(SMTP_PORT);
    }

    /** Full message, with {@code From}, {@code To}, {@code Subject}, {@code Text} and {@code HTML}. */
    public Optional<JsonNode> findBySubject(String subject) {
        JsonNode list = api().get().uri("/api/v1/messages").retrieve().body(JsonNode.class);
        for (JsonNode summary : list.get("messages")) {
            if (subject.equals(summary.get("Subject").asString())) {
                String id = summary.get("ID").asString();
                return Optional.of(
                        api().get().uri("/api/v1/message/{id}", id).retrieve().body(JsonNode.class));
            }
        }
        return Optional.empty();
    }

    /** Values of one header of a stored message, matched case-insensitively. */
    public List<String> header(String messageId, String name) {
        JsonNode headers = api().get()
                .uri("/api/v1/message/{id}/headers", messageId)
                .retrieve()
                .body(JsonNode.class);
        for (String key : headers.propertyNames()) {
            if (key.equalsIgnoreCase(name)) {
                return headers.get(key).valueStream().map(JsonNode::asString).toList();
            }
        }
        return List.of();
    }

    private RestClient api() {
        return RestClient.create("http://" + getHost() + ":" + getMappedPort(HTTP_PORT));
    }
}
