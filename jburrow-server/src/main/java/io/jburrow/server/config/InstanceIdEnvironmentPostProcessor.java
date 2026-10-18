package io.jburrow.server.config;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Draws this start's instance id into the environment before the logging system initializes, so structured logs can
 * carry it on every line from every thread, and the {@code InstanceId} bean reads the same value.
 */
class InstanceIdEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY = "jburrow.instance.id";

    private static final String SOURCE_NAME = "jburrowInstance";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        SOURCE_NAME, Map.of(PROPERTY, UUID.randomUUID().toString())));
    }
}
