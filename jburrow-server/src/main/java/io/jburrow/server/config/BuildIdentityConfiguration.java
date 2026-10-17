package io.jburrow.server.config;

import io.jburrow.server.api.instance.application.port.BuildIdentitySource;
import io.jburrow.server.api.instance.domain.BuildIdentity;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class BuildIdentityConfiguration {

    static final String UNKNOWN = "unknown";

    /** Build info and git info are absent when running from an IDE without a Maven build; both fall back. */
    @Bean
    BuildIdentitySource buildIdentitySource(
            ObjectProvider<BuildProperties> buildProperties,
            ObjectProvider<GitProperties> gitProperties,
            Environment environment) {
        BuildProperties build = buildProperties.getIfAvailable();
        GitProperties git = gitProperties.getIfAvailable();
        String version = build != null && build.getVersion() != null ? build.getVersion() : UNKNOWN;
        String commit = git != null && git.getShortCommitId() != null ? git.getShortCommitId() : UNKNOWN;
        String[] active = environment.getActiveProfiles();
        List<String> profiles = List.of(active.length > 0 ? active : environment.getDefaultProfiles());
        BuildIdentity identity = new BuildIdentity(version, commit, profiles);
        return () -> identity;
    }
}
