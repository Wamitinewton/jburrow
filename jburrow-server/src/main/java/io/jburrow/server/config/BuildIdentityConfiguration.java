package io.jburrow.server.config;

import io.jburrow.server.api.instance.application.port.BuildIdentitySource;
import io.jburrow.server.api.instance.domain.BuildIdentity;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class BuildIdentityConfiguration {

    static final String UNKNOWN = "unknown";

    /**
     * Version and commit come from {@code build-info.properties}. The commit is {@code build.commit}, taken from
     * git when Maven builds from a checkout and from {@code -Djburrow.git.commit} in the image build, which has no
     * {@code .git}. Both fall back when running from an IDE without a Maven build, or from sources without git.
     */
    @Bean
    BuildIdentitySource buildIdentitySource(ObjectProvider<BuildProperties> buildProperties, Environment environment) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? resolvedOrUnknown(build.getVersion()) : UNKNOWN;
        String commit = build != null ? resolvedOrUnknown(build.get("commit")) : UNKNOWN;
        String[] active = environment.getActiveProfiles();
        List<String> profiles = List.of(active.length > 0 ? active : environment.getDefaultProfiles());
        BuildIdentity identity = new BuildIdentity(version, commit, profiles);
        return () -> identity;
    }

    /** An unresolved Maven expression, such as {@code ${git.commit.id.abbrev}} without git, counts as unknown. */
    static String resolvedOrUnknown(String value) {
        return value == null || value.isBlank() || value.contains("${") ? UNKNOWN : value;
    }
}
