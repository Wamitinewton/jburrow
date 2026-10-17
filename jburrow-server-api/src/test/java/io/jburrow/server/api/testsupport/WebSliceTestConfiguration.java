package io.jburrow.server.api.testsupport;

import io.jburrow.server.api.shared.web.ApiPrefixConfiguration;
import io.jburrow.server.api.shared.web.GlobalExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Wires the shared web conventions the way the composition root does, for {@code @WebMvcTest} slices. */
@Configuration(proxyBeanMethods = false)
@Import({GlobalExceptionHandler.class, TestController.class})
public class WebSliceTestConfiguration {

    public static final String PREFIX = "/api/v1";

    @Bean
    ApiPrefixConfiguration apiPrefixConfiguration() {
        return new ApiPrefixConfiguration(PREFIX);
    }
}
