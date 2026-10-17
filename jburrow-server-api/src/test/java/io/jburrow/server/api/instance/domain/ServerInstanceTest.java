package io.jburrow.server.api.instance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerInstanceTest {

    private static final BuildIdentity BUILD = new BuildIdentity("0.1.0", "abc1234", List.of("local"));
    private static final Instant STARTED_AT = Instant.parse("2026-10-17T08:00:00Z");

    @Test
    void requiresEveryField() {
        assertThatNullPointerException().isThrownBy(() -> new ServerInstance(null, BUILD, STARTED_AT));
        assertThatNullPointerException().isThrownBy(() -> new ServerInstance(InstanceId.random(), null, STARTED_AT));
        assertThatNullPointerException().isThrownBy(() -> new ServerInstance(InstanceId.random(), BUILD, null));
    }

    @Test
    void randomInstanceIdsDiffer() {
        assertThat(InstanceId.random()).isNotEqualTo(InstanceId.random());
    }

    @Test
    void instanceIdRequiresAValue() {
        assertThatNullPointerException().isThrownBy(() -> new InstanceId(null));
    }
}
