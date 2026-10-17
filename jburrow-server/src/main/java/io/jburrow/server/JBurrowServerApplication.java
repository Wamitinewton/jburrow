package io.jburrow.server;

import io.jburrow.server.config.JBurrowProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(JBurrowProperties.class)
public class JBurrowServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JBurrowServerApplication.class, args);
    }
}
