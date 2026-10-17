package io.jburrow.server.config;

import io.jburrow.server.api.shared.web.ApiPrefixConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class WebConfiguration {

    @Bean
    ApiPrefixConfiguration apiPrefixConfiguration(JBurrowProperties properties) {
        return new ApiPrefixConfiguration(properties.api().prefix());
    }

    @Bean
    OpenAPI jburrowOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "unknown";
        return new OpenAPI().info(new Info().title("JBurrow API").version(version));
    }
}
