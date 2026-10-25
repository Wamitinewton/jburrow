package io.jburrow.server.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The real dependencies for integration tests. Import this rather than declaring another container. */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfiguration {

    public static final String POSTGRES_IMAGE = "postgres:17-alpine";
    public static final String MAILPIT_IMAGE = "axllent/mailpit:v1.31.6";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }

    @Bean
    MailpitContainer mailpit() {
        return new MailpitContainer(MAILPIT_IMAGE);
    }

    /** Boot has no service connection for SMTP, so the mapped port is registered as plain properties. */
    @Bean
    DynamicPropertyRegistrar mailpitProperties(MailpitContainer mailpit) {
        return registry -> {
            registry.add("spring.mail.host", mailpit::getHost);
            registry.add("spring.mail.port", mailpit::smtpPort);
        };
    }

    /** An ephemeral edge port, so test contexts never collide with each other or a server running locally. */
    @Bean
    DynamicPropertyRegistrar edgeProperties() {
        return registry -> registry.add("jburrow.edge.port", () -> 0);
    }
}
