package io.jburrow.server.api.instance.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.api.instance.application.DescribeInstance;
import io.jburrow.server.api.instance.application.InMemoryServerInstanceStore;
import io.jburrow.server.api.instance.domain.BuildIdentity;
import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.api.instance.domain.ServerInstance;
import io.jburrow.server.api.testsupport.WebSliceTestConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest
@ContextConfiguration(classes = {WebSliceTestConfiguration.class, MetaControllerTest.Slice.class})
class MetaControllerTest {

    private static final InstanceId CURRENT = new InstanceId(UUID.fromString("0f8e5c1a-3b6d-4d2e-9a7f-1c2b3d4e5f60"));

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private InMemoryServerInstanceStore store;

    @BeforeEach
    void emptyStore() {
        store.clear();
    }

    @Test
    void returnsTheRecordedInstanceInTheEnvelopeWithoutProfiles() {
        store.save(new ServerInstance(
                CURRENT,
                new BuildIdentity("0.1.0", "abc1234", List.of("prod")),
                Instant.parse("2026-10-17T08:00:00Z")));

        assertThat(mvc.get().uri("/api/v1/meta")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                        {
                          "success": true,
                          "message": "Instance",
                          "data": {
                            "instanceId": "0f8e5c1a-3b6d-4d2e-9a7f-1c2b3d4e5f60",
                            "version": "0.1.0",
                            "commit": "abc1234",
                            "startedAt": "2026-10-17T08:00:00Z"
                          }
                        }
                        """);
    }

    @Test
    void reportsAnUnrecordedInstance() {
        assertThat(mvc.get().uri("/api/v1/meta")).hasStatus(404).bodyJson().isLenientlyEqualTo("""
                        {"success": false, "code": "INSTANCE_NOT_RECORDED", "fieldErrors": [], "path": "/api/v1/meta"}
                        """);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(MetaController.class)
    static class Slice {

        @Bean
        InMemoryServerInstanceStore store() {
            return new InMemoryServerInstanceStore();
        }

        @Bean
        DescribeInstance describeInstance(InMemoryServerInstanceStore store) {
            return new DescribeInstance(store, CURRENT);
        }
    }
}
