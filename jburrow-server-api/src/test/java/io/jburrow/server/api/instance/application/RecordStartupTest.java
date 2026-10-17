package io.jburrow.server.api.instance.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.api.instance.domain.BuildIdentity;
import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.api.instance.domain.ServerInstance;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordStartupTest {

    private static final Instant NOW = Instant.parse("2026-10-17T08:00:00Z");
    private static final BuildIdentity BUILD = new BuildIdentity("0.1.0", "abc1234", List.of("prod"));

    private final InMemoryServerInstanceStore store = new InMemoryServerInstanceStore();
    private final InstanceId current = InstanceId.random();
    private final RecordStartup recordStartup =
            new RecordStartup(store, () -> BUILD, Clock.fixed(NOW, ZoneOffset.UTC), current);

    @Test
    void savesTheCurrentInstanceWithBuildAndTime() {
        ServerInstance recorded = recordStartup.execute();

        assertThat(recorded).isEqualTo(new ServerInstance(current, BUILD, NOW));
        assertThat(store.find(current)).contains(recorded);
        assertThat(store.size()).isOne();
    }
}
