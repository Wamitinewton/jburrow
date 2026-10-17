package io.jburrow.server.api.instance.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.api.instance.domain.BuildIdentity;
import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.api.instance.domain.ServerInstance;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ServerInstanceEntityTest {

    static List<List<String>> profiles() {
        return List.of(List.of("local"), List.of("prod", "kubernetes"));
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void mapsToTheDomainAndBack(List<String> profiles) {
        ServerInstance instance = new ServerInstance(
                InstanceId.random(),
                new BuildIdentity("0.1.0", "abc1234", profiles),
                Instant.parse("2026-10-17T08:00:00.123456Z"));

        assertThat(ServerInstanceEntity.fromDomain(instance).toDomain()).isEqualTo(instance);
    }
}
