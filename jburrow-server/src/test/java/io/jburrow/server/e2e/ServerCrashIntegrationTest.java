package io.jburrow.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.ReconnectPolicy;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.Register;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.e2e.RecordingEvents.Reconnected;
import io.jburrow.server.e2e.support.ServerContexts;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * A crash under open tunnels ({@code docs/phase-4-staying-connected/ARCHITECTURE.md} §Testing, §Failure modes): no
 * {@code GOAWAY}, no drain, no final flush. The new process seeds what the old one's last periodic write left, so the
 * clients' name and port written before it come back to them, and another principal is refused both; a claim made
 * after that write, which no row recorded, is free in the new process to whoever asks first.
 *
 * <p>Without {@code GOAWAY} the clients see a lost connection and back off, so the schedule is scaled to a tenth: the
 * test proves the leases, not the length of a backoff.
 */
class ServerCrashIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(properties = {"jburrow.tcp.port-range=32100-32109", "jburrow.edge.transport=epoll"})
    class OnEpoll extends Scenario {
        OnEpoll() {
            super(new ServerContexts.Ports(32_110), ON_EPOLL);
        }
    }

    @Nested
    @TestPropertySource(properties = {"jburrow.tcp.port-range=32130-32139", "jburrow.edge.transport=nio"})
    class OnNio extends Scenario {
        OnNio() {
            super(new ServerContexts.Ports(32_140), ON_NIO);
        }
    }

    @Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    abstract static class Scenario extends TunnelTestSupport {

        static final Duration BACK_WITHIN = Duration.ofSeconds(5);
        private static final ReconnectPolicy TENTH = ReconnectPolicy.standard().scaled(Duration.ofMillis(100));

        @Autowired
        private JdbcClient jdbc;

        private final ServerContexts.Ports ports;
        private final String transport;

        Scenario(ServerContexts.Ports ports, String transport) {
            this.ports = ports;
            this.transport = transport;
        }

        @Test
        void theLastWriteIsHonouredAndAClaimMadeAfterItIsAnyonesToTake() throws Exception {
            ServerContexts servers = serverContexts(ports, transport);
            servers.start();
            LocalService echo = closeAfterTest(LocalService.echo());
            Tunnel http = openTunnel(httpClient(ALICE_TOKEN, unusedPort())
                    .server(servers.connectUri())
                    .reconnect(TENTH));
            Tunnel tcp = openTunnel(client(ALICE_TOKEN, tcp(echo.port()))
                    .server(servers.connectUri())
                    .reconnect(TENTH));
            String name = label(http.host());
            int port = tcp.port();
            UUID crashed = servers.instanceId();
            await(
                    "the process has written both claims",
                    () -> leasesOf(crashed).containsAll(List.of("name " + name, "port " + port)));

            servers.freezeLeases();
            String late = "late-claim-" + ports.first();
            ScriptedPeer lateClaim = helloed(servers.directConnectUri(), List.of(Capability.HTTP));
            lateClaim.send(new Frame.Register(new Register(ALICE_TOKEN, List.of(httpRequest(late)))));
            lateClaim.expect(Frame.Registered.class, TIMEOUT);
            servers.kill();

            assertThat(leasesOf(crashed)).as("what the crashed process left").doesNotContain("name " + late);
            servers.start(direct -> {
                assertThat(refusal(direct, BOB_TOKEN, httpRequest(name))).isEqualTo(ErrorCode.SUBDOMAIN_UNAVAILABLE);
                assertThat(refusal(direct, BOB_TOKEN, tcpRequest(port))).isEqualTo(ErrorCode.PORT_UNAVAILABLE);
                ScriptedPeer first = helloed(direct, List.of(Capability.HTTP));
                first.send(new Frame.Register(new Register(BOB_TOKEN, List.of(httpRequest(late)))));
                assertThat(first.expect(Frame.Registered.class, TIMEOUT)
                                .message()
                                .tunnels()
                                .getFirst()
                                .publicHost())
                        .startsWith(late + ".");
            });
            long open = System.nanoTime();
            Reconnected httpBack = http.events().awaitReconnected(BACK_WITHIN);
            Reconnected tcpBack = tcp.events().awaitReconnected(BACK_WITHIN);

            assertThat(Duration.ofNanos(Math.max(httpBack.atNanos(), tcpBack.atNanos()) - open))
                    .isLessThan(BACK_WITHIN);
            assertThat(httpBack.sameUrls()).isTrue();
            assertThat(label(httpBack.tunnels().getFirst().publicHost())).isEqualTo(name);
            assertThat(tcpBack.sameUrls()).isTrue();
            assertThat(tcpBack.tunnels().getFirst().publicPort()).isEqualTo(port);
            for (Tunnel tunnel : new Tunnel[] {http, tcp}) {
                assertThat(tunnel.events().disconnects().getFirst().reason().reason())
                        .as("no word from a crashed server")
                        .isEqualTo(TunnelClosed.Reason.CONNECTION_LOST);
                assertThat(tunnel.events().reconnects()).hasSize(1);
                assertOpen(tunnel.handle());
            }
        }

        /** {@code kind key} for each of {@code instance}'s rows. */
        private List<String> leasesOf(UUID instance) {
            return jdbc.sql("select kind || ' ' || lease_key from jburrow.tunnel_lease where instance_id = ?")
                    .param(instance)
                    .query(String.class)
                    .list();
        }
    }
}
