package io.jburrow.server.e2e;

import static io.jburrow.server.e2e.TunnelTestSupport.HTTP_PORTS_ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.HTTP_PORTS_ON_NIO;
import static io.jburrow.server.e2e.TunnelTestSupport.KERNEL_SOCKET_BUFFERS;
import static io.jburrow.server.e2e.TunnelTestSupport.LOOPBACK_IS_THE_INGRESS;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_NIO;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.control.TunnelKind;
import java.net.Socket;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.TestPropertySource;

/**
 * A client that doesn't offer {@code http}, as a phase 2 CLI doesn't, gets TCP tunnels from this server exactly as
 * before ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Why a capability, not a new draft): the real client,
 * built to offer nothing, through the full server.
 */
class Phase2ClientIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(properties = {HTTP_PORTS_ON_EPOLL, ON_EPOLL, LOOPBACK_IS_THE_INGRESS, KERNEL_SOCKET_BUFFERS})
    class OnEpoll extends Scenario {}

    @Nested
    @TestPropertySource(properties = {HTTP_PORTS_ON_NIO, ON_NIO, LOOPBACK_IS_THE_INGRESS, KERNEL_SOCKET_BUFFERS})
    class OnNio extends Scenario {}

    abstract static class Scenario extends TunnelTestSupport {

        @Test
        void aClientWithoutHttpRunsATcpTunnelAsBefore() throws Exception {
            LocalService echo = closeAfterTest(LocalService.echo());
            double opened = counter("jburrow.streams.opened", "kind", "tcp");

            Tunnel tunnel = openTunnel(client(ALICE_TOKEN, tcp(echo.port())).capabilities(Set.of()));
            byte[] message = "phase 2 bytes, phase 3 server".getBytes(US_ASCII);
            Socket visitor = visit(tunnel.port());

            assertThat(tunnel.registered().kind()).isEqualTo(TunnelKind.TCP);
            assertThat(tunnel.registered().url()).isEqualTo("tcp://localhost:" + tunnel.port());
            assertThat(roundTrip(visitor, message)).isEqualTo(message);
            assertThat(counter("jburrow.streams.opened", "kind", "tcp")).isEqualTo(opened + 1);
            visitor.shutdownOutput();
            assertThat(visitor.getInputStream().read()).isEqualTo(-1);
            assertOpen(tunnel.handle());
        }
    }
}
