package io.jburrow.server.config;

import io.jburrow.server.api.instance.application.port.BuildIdentitySource;
import io.jburrow.server.api.instance.domain.BuildIdentity;
import io.jburrow.server.api.instance.domain.InstanceId;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The instance tag on every meter, the build info metric, and the startup line naming what is running. */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityConfiguration.class);

    @Bean
    MeterRegistryCustomizer<MeterRegistry> instanceTag(InstanceId currentInstanceId) {
        return registry -> registry.config().commonTags("instance", currentInstanceId.toString());
    }

    @Bean
    MeterBinder buildInfo(BuildIdentitySource identity) {
        return registry -> {
            BuildIdentity build = identity.current();
            Gauge.builder("jburrow.build.info", () -> 1)
                    .description("Version and commit of the running build; always 1")
                    .tag("version", build.version())
                    .tag("commit", build.commit())
                    .register(registry);
        };
    }

    @Bean
    ApplicationListener<ApplicationStartedEvent> startupLine(BuildIdentitySource identity, InstanceId instanceId) {
        return event -> {
            BuildIdentity build = identity.current();
            log.info(
                    "jburrow-server {} ({}) instance {} profiles {}",
                    build.version(),
                    build.commit(),
                    instanceId,
                    String.join(",", build.profiles()));
        };
    }
}
