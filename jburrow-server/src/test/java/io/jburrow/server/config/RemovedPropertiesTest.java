package io.jburrow.server.config;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class RemovedPropertiesTest {

    @Test
    void theNameHoldIsRefusedNamingItsReplacement() {
        MockEnvironment environment = new MockEnvironment().withProperty("jburrow.http.name-hold", "60s");

        assertThatIllegalStateException()
                .isThrownBy(() -> RemovedProperties.refuse(environment))
                .withMessage("jburrow.http.name-hold is replaced by jburrow.session.resume-window");
    }

    @Test
    void theNameHoldIsRefusedAsAnEnvironmentVariable() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("JBURROW_HTTP_NAMEHOLD", "60s")));

        assertThatIllegalStateException()
                .isThrownBy(() -> RemovedProperties.refuse(environment))
                .withMessageContaining("jburrow.session.resume-window");
    }

    @Test
    void anEnvironmentWithoutRemovedKeysPasses() {
        RemovedProperties.refuse(new MockEnvironment()
                .withProperty("jburrow.session.resume-window", "120s")
                .withProperty("jburrow.http.domain", "example.dev"));
    }
}
