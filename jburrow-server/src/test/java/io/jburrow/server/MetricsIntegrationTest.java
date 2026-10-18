package io.jburrow.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.support.TestContainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestContainersConfiguration.class)
class MetricsIntegrationTest {

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private InstanceId currentInstanceId;

    @Test
    void buildInfoIsExportedWithVersionAndCommit() {
        List<String> buildInfo = series("jburrow_build_info{");

        assertThat(buildInfo).singleElement().satisfies(line -> {
            assertThat(line).containsPattern("version=\"[^\"]+\"");
            assertThat(line).containsPattern("commit=\"[^\"]+\"");
            assertThat(line).endsWith(" 1");
        });
    }

    @Test
    void jvmMetricsCarryTheApplicationAndInstanceTags() {
        List<String> jvm = series("jvm_memory_used_bytes{");

        assertThat(jvm)
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line)
                        .contains("application=\"jburrow-server\"", "instance=\"" + currentInstanceId + "\""));
    }

    private List<String> series(String prefix) {
        String scrape = RestClient.create("http://localhost:" + managementPort)
                .get()
                .uri("/actuator/prometheus")
                .retrieve()
                .body(String.class);
        return scrape.lines().filter(line -> line.startsWith(prefix)).toList();
    }
}
