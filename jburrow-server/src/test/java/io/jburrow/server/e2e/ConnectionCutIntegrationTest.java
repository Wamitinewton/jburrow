package io.jburrow.server.e2e;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.ReconnectPolicy;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.e2e.RecordingEvents.Entry;
import io.jburrow.server.e2e.RecordingEvents.Reconnected;
import io.jburrow.server.e2e.RecordingEvents.StreamClosed;
import io.jburrow.server.e2e.support.CuttingProxy;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.TestPropertySource;

/**
 * A connection cut between a client and a server that keeps running, as an ingress reload or a dropped Wi-Fi link cuts
 * it ({@code docs/phase-4-staying-connected/ARCHITECTURE.md} §Failure modes): the client reports the open stream closed
 * before the disconnect, tries again within half a second, and is back on the same server with the same port, which
 * its owner's hold kept for it, in under a second.
 */
class ConnectionCutIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(properties = {"jburrow.tcp.port-range=32220-32229", "jburrow.edge.transport=epoll"})
    class OnEpoll extends Scenario {}

    @Nested
    @TestPropertySource(properties = {"jburrow.tcp.port-range=32230-32239", "jburrow.edge.transport=nio"})
    class OnNio extends Scenario {}

    @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    abstract static class Scenario extends TunnelTestSupport {

        static final Duration BACK_WITHIN = Duration.ofSeconds(1);

        @Test
        void theClientIsBackWithinASecondWithTheSamePortAndItsStreamsReportedClosedFirst() throws Exception {
            CuttingProxy path = closeAfterTest(CuttingProxy.to(edge.boundPort()));
            LocalService echo = closeAfterTest(LocalService.echo());
            Tunnel tunnel = openTunnel(client(ALICE_TOKEN, tcp(echo.port()))
                    .server(URI.create(
                            "ws://localhost:" + path.port() + connectUri().getPath()))
                    .reconnect(ReconnectPolicy.standard()));
            int port = tunnel.port();
            String sessionBefore = tunnel.handle().sessionId();
            Socket visitor = visit(port);
            byte[] before = "before the cut".getBytes(US_ASCII);
            assertThat(roundTrip(visitor, before)).isEqualTo(before);

            long cut = System.nanoTime();
            path.cut();

            Reconnected back = tunnel.events().awaitReconnected(BACK_WITHIN.plus(TIMEOUT));
            assertThat(Duration.ofNanos(back.atNanos() - cut)).isLessThan(BACK_WITHIN);
            assertThat(back.sameUrls()).isTrue();
            assertThat(back.tunnels().getFirst().publicPort()).isEqualTo(port);
            assertThat(back.sessionId()).isNotEqualTo(sessionBefore);
            assertThat(tunnel.handle().sessionId()).isEqualTo(back.sessionId());

            StreamClosed stream = tunnel.events().awaitStreamClosed(TIMEOUT);
            assertThat(stream.reset()).contains(ErrorCode.CANCELLED);
            assertThat(tunnel.events().disconnects())
                    .singleElement()
                    .satisfies(disconnect ->
                            assertThat(disconnect.reason().reason()).isEqualTo(TunnelClosed.Reason.CONNECTION_LOST));
            List<String> order = tunnel.events().timeline().stream()
                    .map(Entry::kind)
                    .filter(kind -> !kind.equals("streamOpened"))
                    .toList();
            assertThat(order).containsExactly("streamClosed", "disconnected", "reconnected");

            byte[] after = "after the cut".getBytes(US_ASCII);
            assertThat(roundTrip(visit(port), after)).isEqualTo(after);
            assertOpen(tunnel.handle());
        }
    }
}
