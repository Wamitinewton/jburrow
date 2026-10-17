package io.jburrow.server.api.instance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.server.api.instance.domain.BuildIdentity;
import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.api.instance.domain.ServerInstance;
import io.jburrow.server.api.shared.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DescribeInstanceTest {

    private final InMemoryServerInstanceStore store = new InMemoryServerInstanceStore();
    private final InstanceId current = InstanceId.random();
    private final DescribeInstance describeInstance = new DescribeInstance(store, current);

    @Test
    void returnsTheCurrentInstance() {
        ServerInstance instance = new ServerInstance(
                current, new BuildIdentity("0.1.0", "abc1234", List.of("prod")), Instant.parse("2026-10-17T08:00:00Z"));
        store.save(instance);
        store.save(new ServerInstance(InstanceId.random(), instance.build(), Instant.parse("2026-10-16T08:00:00Z")));

        assertThat(describeInstance.execute()).isEqualTo(instance);
    }

    @Test
    void failsWhenTheStartupWasNotRecorded() {
        assertThatThrownBy(describeInstance::execute)
                .isInstanceOfSatisfying(
                        InstanceNotRecordedException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INSTANCE_NOT_RECORDED))
                .hasMessageContaining(current.toString());
    }
}
