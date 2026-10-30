package io.jburrow.server;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

class ProdProfileStartupIntegrationTest {

    /**
     * The binder keeps an unresolvable placeholder as literal text, so the failure may come from validation of the
     * bound value rather than from placeholder resolution. Either way the message carries the placeholder, except for
     * the datasource: Hikari is created first (the entity manager factory pulls it in early) and rejects the literal
     * {@code ${SPRING_DATASOURCE_URL}} with a message naming the URL.
     */
    private static final String REQUIRED_PLACEHOLDER = "\\$\\{(SPRING_DATASOURCE_URL|SPRING_DATASOURCE_USERNAME"
            + "|SPRING_DATASOURCE_PASSWORD|SPRING_MAIL_HOST|SPRING_MAIL_PORT|SPRING_MAIL_USERNAME"
            + "|SPRING_MAIL_PASSWORD|JBURROW_MAIL_FROM_ADDRESS|JBURROW_AUTH_STATIC_TOKENS"
            + "|JBURROW_EDGE_CONNECT_HOST|JBURROW_EDGE_TRUSTED_PROXIES|JBURROW_TCP_PORT_RANGE|JBURROW_TCP_PUBLIC_HOST"
            + "|JBURROW_HTTP_DOMAIN)}"
            + "|'url' must start with \"jdbc\"";

    @Test
    void prodRefusesToStartWithoutItsEnvironment() {
        SpringApplication application = new SpringApplication(JBurrowServerApplication.class);
        application.setAdditionalProfiles("prod");

        assertThatThrownBy(() -> application
                        .run("--server.port=0", "--management.server.port=0")
                        .close())
                .rootCause()
                .message()
                .containsPattern(REQUIRED_PLACEHOLDER);
    }
}
