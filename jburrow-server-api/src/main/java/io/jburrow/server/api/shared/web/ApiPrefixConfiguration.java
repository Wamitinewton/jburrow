package io.jburrow.server.api.shared.web;

import java.util.Objects;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Prefixes every JBurrow {@code @RestController} with the API prefix, so a controller maps {@code /meta}, never
 * {@code /api/v1/meta}. Controllers outside {@code io.jburrow} (springdoc) and Actuator keep their own paths.
 */
public class ApiPrefixConfiguration implements WebMvcConfigurer {

    private final String prefix;

    public ApiPrefixConfiguration(String prefix) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(
                prefix,
                HandlerTypePredicate.forBasePackage("io.jburrow")
                        .and(HandlerTypePredicate.forAnnotation(RestController.class)));
    }
}
