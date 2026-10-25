package io.jburrow.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeServer;
import io.jburrow.server.support.TestContainersConfiguration;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestContainersConfiguration.class)
class EdgeLifecycleIntegrationTest {

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private EdgeServer edge;

    @Test
    void theEdgeAcceptsAfterStartup() {
        assertThat(edge.isAccepting()).isTrue();
        assertThat(edge.boundPort()).isPositive();
        assertThat(accepts(edge.boundPort())).isTrue();
    }

    @Test
    void edgeHealthIsUpWithItsDetails() {
        JsonNode edgeHealth = health("").at("/components/edge");

        assertThat(edgeHealth.get("status").asString()).isEqualTo("UP");
        assertThat(edgeHealth.at("/details/transport").asString()).isIn("epoll", "nio");
        assertThat(edgeHealth.at("/details/port").asInt()).isEqualTo(edge.boundPort());
        assertThat(edgeHealth.at("/details/sessions").isNumber()).isTrue();
        assertThat(edgeHealth.at("/details/handshaking").isNumber()).isTrue();
    }

    @Test
    void readinessLeavesTheEdgeOut() {
        assertThat(health("/readiness").get("components").propertyNames()).containsExactly("readinessState");
    }

    @Test
    void theEngineMetricsAreExported() {
        String scrape = RestClient.create("http://localhost:" + managementPort)
                .get()
                .uri("/actuator/prometheus")
                .retrieve()
                .body(String.class);

        assertThat(scrape)
                .contains("jburrow_sessions_active{")
                .contains("jburrow_session_handshakes_total{")
                .contains("jburrow_netty_direct_memory_bytes{")
                .contains("jburrow_tcp_ports{")
                .contains("jburrow_tunnel_bytes_total{")
                .contains("jburrow_register_duration_seconds_bucket{");
    }

    /**
     * Runs a second application against this test's Postgres and Mailpit and closes it, so the cached test context is
     * never closed under the context cache.
     */
    @Test
    void theEdgeStartsAfterTheWebServerAndStopsBeforeIt(
            @Autowired JdbcConnectionDetails db, @Autowired Environment environment) {
        ConfigurableApplicationContext application = new SpringApplicationBuilder(
                        JBurrowServerApplication.class, Recorders.class)
                .run(
                        "--spring.datasource.url=" + db.getJdbcUrl(),
                        "--spring.datasource.username=" + db.getUsername(),
                        "--spring.datasource.password=" + db.getPassword(),
                        "--spring.mail.host=" + environment.getProperty("spring.mail.host"),
                        "--spring.mail.port=" + environment.getProperty("spring.mail.port"),
                        "--server.port=0",
                        "--management.server.port=0",
                        "--jburrow.edge.port=0");
        Recorders recorders = application.getBean(Recorders.class);
        recorders.edgePort = application.getBean(EdgeServer.class).boundPort();
        recorders.apiPort =
                ((WebServerApplicationContext) application).getWebServer().getPort();

        application.close();

        assertThat(recorders.events)
                .containsExactly(
                        "web server started; edge accepting false",
                        "web server stopping; edge accepting false, edge port open false, api port open true");
        assertThat(accepts(recorders.edgePort)).isFalse();
    }

    private JsonNode health(String group) {
        return RestClient.create("http://localhost:" + managementPort)
                .get()
                .uri("/actuator/health" + group)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, reply) -> {})
                .body(JsonNode.class);
    }

    private static boolean accepts(int port) {
        try (Socket ignored = new Socket(InetAddress.getLoopbackAddress(), port)) {
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    /**
     * Records what the edge is doing when the web server has started, and just before the web server stops. Not a
     * {@code @Configuration}, so this test's own context doesn't pick it up.
     */
    static class Recorders {

        final List<String> events = new CopyOnWriteArrayList<>();
        volatile int edgePort;
        volatile int apiPort;

        /** The management server starts its own web server in a child context; only the API's counts. */
        @Bean
        ApplicationListener<ServletWebServerInitializedEvent> webServerStarted(ObjectProvider<EdgeServer> edge) {
            return event -> {
                if (event.getApplicationContext().getServerNamespace() == null) {
                    events.add("web server started; edge accepting "
                            + edge.getObject().isAccepting());
                }
            };
        }

        @Bean
        SmartLifecycle webServerStopping(ObjectProvider<EdgeServer> edge) {
            return new SmartLifecycle() {
                private volatile boolean running;

                @Override
                public void start() {
                    running = true;
                }

                @Override
                public void stop() {
                    running = false;
                    events.add("web server stopping; edge accepting "
                            + edge.getObject().isAccepting() + ", edge port open " + accepts(edgePort)
                            + ", api port open " + accepts(apiPort));
                }

                @Override
                public boolean isRunning() {
                    return running;
                }

                /** Stopped right before the web server's graceful shutdown, the first step of stopping it. */
                @Override
                public int getPhase() {
                    return WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE + 1;
                }
            };
        }
    }
}
