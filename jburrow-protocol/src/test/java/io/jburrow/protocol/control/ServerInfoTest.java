package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServerInfoTest {

    @Test
    void acceptsTheArchitectureExample() {
        assertThat(new ServerInfo("jburrow-server", "0.2.0").name()).isEqualTo("jburrow-server");
    }

    @Test
    void followsTheClientNameAndVersionRules() {
        new ServerInfo("s".repeat(64), "1.0.0+abc");
        assertInvalid(() -> new ServerInfo(null, "1"), "name is required");
        assertInvalid(() -> new ServerInfo("s".repeat(65), "1"), "name must match [A-Za-z0-9._-]{1,64}");
        assertInvalid(() -> new ServerInfo("s", null), "version is required");
        assertInvalid(() -> new ServerInfo("s", ""), "version must match [A-Za-z0-9.+_-]{1,64}");
    }
}
