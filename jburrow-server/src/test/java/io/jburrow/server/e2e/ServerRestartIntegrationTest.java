package io.jburrow.server.e2e;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.ReconnectPolicy;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.e2e.RecordingEvents.Disconnected;
import io.jburrow.server.e2e.RecordingEvents.Reconnected;
import io.jburrow.server.e2e.support.HttpVisitor;
import io.jburrow.server.e2e.support.ServerContexts;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.TestPropertySource;

/**
 * A deploy under open tunnels ({@code docs/phase-4-staying-connected/ARCHITECTURE.md} §Testing): a real client with an
 * HTTP tunnel on a name the server chose, and one with a TCP tunnel on a port it chose, on a server that is stopped
 * through the drain and replaced by a new process on the same ports and the same Postgres. Before the clients can reach
 * the new process, another principal asks it for the name and the port and is refused both: the holds were seeded from
 * the leases before it accepted a connection. Then both clients are back, by themselves, with the same URL and port.
 *
 * <p>The clients reconnect on the standard schedule, unscaled: after {@code GOAWAY}, about once a second.
 */
class ServerRestartIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(properties = {"jburrow.tcp.port-range=32040-32049", "jburrow.edge.transport=epoll"})
    class OnEpoll extends Scenario {
        OnEpoll() {
            super(new ServerContexts.Ports(32_050), ON_EPOLL);
        }
    }

    @Nested
    @TestPropertySource(properties = {"jburrow.tcp.port-range=32070-32079", "jburrow.edge.transport=nio"})
    class OnNio extends Scenario {
        OnNio() {
            super(new ServerContexts.Ports(32_080), ON_NIO);
        }
    }

    @Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    abstract static class Scenario extends TunnelTestSupport {

        /** From the moment the new process lets clients through: the next attempt on the {@code GOAWAY} cadence. */
        static final Duration BACK_WITHIN = Duration.ofSeconds(5);

        private final ServerContexts.Ports ports;
        private final String transport;

        Scenario(ServerContexts.Ports ports, String transport) {
            this.ports = ports;
            this.transport = transport;
        }

        @Test
        void bothClientsComeBackWithTheSameUrlAndPortAndNobodyElseCanTakeThemInBetween() throws Exception {
            ServerContexts servers = serverContexts(ports, transport);
            servers.start();
            LocalHttpService web = closeAfterTest(LocalHttpService.start());
            LocalService echo = closeAfterTest(LocalService.echo());
            Tunnel http = openTunnel(httpClient(ALICE_TOKEN, web.port())
                    .server(servers.connectUri())
                    .reconnect(ReconnectPolicy.standard()));
            Tunnel tcp = openTunnel(client(ALICE_TOKEN, tcp(echo.port()))
                    .server(servers.connectUri())
                    .reconnect(ReconnectPolicy.standard()));
            String host = http.host();
            int port = tcp.port();

            servers.stop();

            servers.start(direct -> {
                assertThat(refusal(direct, BOB_TOKEN, httpRequest(label(host))))
                        .isEqualTo(ErrorCode.SUBDOMAIN_UNAVAILABLE);
                assertThat(refusal(direct, BOB_TOKEN, tcpRequest(port))).isEqualTo(ErrorCode.PORT_UNAVAILABLE);
            });
            long open = System.nanoTime();
            Reconnected httpBack = http.events().awaitReconnected(BACK_WITHIN);
            Reconnected tcpBack = tcp.events().awaitReconnected(BACK_WITHIN);

            assertThat(Duration.ofNanos(Math.max(httpBack.atNanos(), tcpBack.atNanos()) - open))
                    .isLessThan(BACK_WITHIN);
            assertThat(httpBack.sameUrls()).isTrue();
            assertThat(httpBack.tunnels().getFirst().publicHost()).isEqualTo(host);
            assertThat(tcpBack.sameUrls()).isTrue();
            assertThat(tcpBack.tunnels().getFirst().publicPort()).isEqualTo(port);
            for (Tunnel tunnel : new Tunnel[] {http, tcp}) {
                assertReconnectedOnceAfterTheDrain(tunnel.events());
                assertOpen(tunnel.handle());
            }

            HttpVisitor visitor = closeAfterTest(HttpVisitor.connect(ports.edge(), TIMEOUT));
            visitor.request("GET", host, "/hello");
            assertThat(visitor.response().text()).isEqualTo("hello\n");
            byte[] message = "after the deploy".getBytes(US_ASCII);
            assertThat(roundTrip(visit(port), message)).isEqualTo(message);
        }

        /**
         * The old session ended after {@code GOAWAY}, with {@code SERVER_SHUTTING_DOWN}; every later report until the
         * reconnect is an attempt the front refused while no process ran; and there was exactly one reconnect.
         */
        static void assertReconnectedOnceAfterTheDrain(RecordingEvents events) {
            Disconnected sessionEnd = events.disconnects().getFirst();
            assertThat(sessionEnd.reason().reason()).isEqualTo(TunnelClosed.Reason.SERVER_ERROR);
            assertThat(sessionEnd.reason().code()).contains(ErrorCode.SERVER_SHUTTING_DOWN);
            assertThat(events.disconnects())
                    .allSatisfy(disconnect -> assertThat(disconnect.atNanos())
                            .isLessThan(events.reconnects().getFirst().atNanos()));
            assertThat(events.reconnects()).hasSize(1);
            assertThat(events.stopped()).isFalse();
        }
    }
}
