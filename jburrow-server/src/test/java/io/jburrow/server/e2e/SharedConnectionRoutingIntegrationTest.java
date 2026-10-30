package io.jburrow.server.e2e;

import static io.jburrow.server.e2e.TunnelTestSupport.HTTP_PORTS_ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.HTTP_PORTS_ON_NIO;
import static io.jburrow.server.e2e.TunnelTestSupport.KERNEL_SOCKET_BUFFERS;
import static io.jburrow.server.e2e.TunnelTestSupport.LOOPBACK_IS_THE_INGRESS;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_NIO;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.e2e.support.HttpVisitor;
import io.jburrow.server.e2e.support.Ingress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.TestPropertySource;

/**
 * Routing per request, not per connection ({@code PROJECT.md} §Routing per request): the ingress reuses its upstream
 * connections for any host, so requests for two tunnels interleave on the same four connections, and each must reach
 * its own tunnel's local service.
 */
class SharedConnectionRoutingIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(properties = {HTTP_PORTS_ON_EPOLL, ON_EPOLL, LOOPBACK_IS_THE_INGRESS, KERNEL_SOCKET_BUFFERS})
    class OnEpoll extends Scenario {}

    @Nested
    @TestPropertySource(properties = {HTTP_PORTS_ON_NIO, ON_NIO, LOOPBACK_IS_THE_INGRESS, KERNEL_SOCKET_BUFFERS})
    class OnNio extends Scenario {}

    abstract static class Scenario extends TunnelTestSupport {

        private static final int CONNECTIONS = 4;
        private static final int REQUESTS = 1_000;
        private static final Duration DEADLINE = Duration.ofMinutes(1);

        @Test
        void requestsForTwoTunnelsOnSharedConnectionsEachReachTheirOwnService() throws Exception {
            LocalHttpService alpha = closeAfterTest(LocalHttpService.start("alpha-marker"));
            LocalHttpService beta = closeAfterTest(LocalHttpService.start("beta-marker"));
            List<String> hosts = List.of(
                    openHttpTunnel(ALICE_TOKEN, alpha.port()).host(),
                    openHttpTunnel(BOB_TOKEN, beta.port()).host());
            List<String> markers = List.of("alpha-marker", "beta-marker");
            Ingress ingress = ingress(CONNECTIONS);

            List<CompletableFuture<Integer>> workers = new ArrayList<>();
            for (int worker = 0; worker < CONNECTIONS; worker++) {
                int first = worker;
                workers.add(async(() -> {
                    int served = 0;
                    for (int i = first; i < REQUESTS; i += CONNECTIONS) {
                        int tunnel = (i / CONNECTIONS + first) % 2;
                        HttpVisitor.Response response = ingress.get(hosts.get(tunnel), "/marker?request=" + i);
                        assertThat(response.text())
                                .as("request %d, for %s", i, hosts.get(tunnel))
                                .isEqualTo(markers.get(tunnel));
                        served++;
                    }
                    return served;
                }));
            }

            assertThat(awaitAll(workers, DEADLINE).stream()
                            .mapToInt(Integer::intValue)
                            .sum())
                    .isEqualTo(REQUESTS);
            assertThat(alpha.received()).hasSize(REQUESTS / 2);
            assertThat(beta.received()).hasSize(REQUESTS / 2);
        }
    }
}
