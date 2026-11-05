package io.jburrow.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.ReconnectPolicy;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.server.e2e.RecordingEvents.Reconnected;
import io.jburrow.server.e2e.support.CuttingProxy;
import io.jburrow.server.e2e.support.CuttingProxy.Direction;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * A laptop that changed networks ({@code docs/phase-4-staying-connected/ARCHITECTURE.md} §What survives a gap): the old
 * connection goes silent without closing, the client gives up on it first and reconnects on a new path with its resume
 * token, and the server ends the silent session ({@code SESSION_REPLACED}) and gives the new one the same URL, before
 * its own idle timeout would have noticed anything.
 *
 * <p>Both sides time out on the idle timeout the server announced, so which one gives up first is decided by who heard
 * from the other last. The path goes silent towards the client first; once the server has had the client's next
 * {@code PING}, which no answer reaches, it goes silent the other way too. The server then last heard from the client a
 * {@code PING} interval after the client last heard from it, and the client's timeout fires that much earlier. Out of
 * that interval the client spends up to 2 seconds waiting for its {@code ERROR}'s close, which the silent path never
 * brings, so the interval is 5 seconds. The client's schedule is scaled down, so its quick first attempt takes nothing
 * from what is left.
 */
class StaleSessionIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(
            properties = {
                "jburrow.tcp.port-range=32240-32249",
                "jburrow.edge.transport=epoll",
                "jburrow.edge.ping-interval=5s",
                "jburrow.edge.idle-timeout=10s"
            })
    class OnEpoll extends Scenario {}

    @Nested
    @TestPropertySource(
            properties = {
                "jburrow.tcp.port-range=32250-32259",
                "jburrow.edge.transport=nio",
                "jburrow.edge.ping-interval=5s",
                "jburrow.edge.idle-timeout=10s"
            })
    class OnNio extends Scenario {}

    @Timeout(value = 1, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @ExtendWith(OutputCaptureExtension.class)
    abstract static class Scenario extends TunnelTestSupport {

        static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);
        /** The idle timeout, the client's wait for its close, and room for a busy machine. */
        static final Duration BACK_WITHIN = IDLE_TIMEOUT.plusSeconds(5);

        @Test
        void aSilentSessionIsReplacedByTheClientsNextOneBeforeTheServerTimesItOut(CapturedOutput output)
                throws Exception {
            CuttingProxy path = closeAfterTest(CuttingProxy.to(edge.boundPort()));
            Tunnel tunnel = openTunnel(httpClient(ALICE_TOKEN, unusedPort())
                    .server(URI.create(
                            "ws://localhost:" + path.port() + connectUri().getPath()))
                    .reconnect(ReconnectPolicy.standard().scaled(Duration.ofMillis(10))));
            String host = tunnel.host();
            String stale = tunnel.handle().sessionId();
            double replacedBefore = counter("jburrow.resume", "outcome", "replaced");

            double pingsBefore = pingsReceived();
            path.blackhole(Direction.FROM_TARGET);
            await("the server has had the client's next PING", () -> pingsReceived() > pingsBefore);
            path.blackhole(Direction.TO_TARGET);

            Reconnected back = tunnel.events().awaitReconnected(BACK_WITHIN);
            assertThat(back.sameUrls()).isTrue();
            assertThat(back.tunnels().getFirst().publicHost()).isEqualTo(host);
            assertThat(tunnel.events().disconnects().getFirst().reason().reason())
                    .as("the client gave up on the silent connection itself")
                    .isEqualTo(TunnelClosed.Reason.HEARTBEAT_TIMEOUT);

            await("the replacement is logged", () -> output.getAll().contains("session replaced: session=" + stale));
            assertThat(output.getAll().lines().filter(line -> line.contains("session replaced: session=" + stale)))
                    .singleElement()
                    .asString()
                    .contains("replacedBy=" + back.sessionId(), "principal=alice", "silentFor=");
            assertThat(output.getAll())
                    .as("the server never timed the silent session out itself")
                    .doesNotContain("session=" + stale + " code=HEARTBEAT_TIMEOUT");
            assertThat(counter("jburrow.resume", "outcome", "replaced")).isEqualTo(replacedBefore + 1);
            assertOpen(tunnel.handle());
        }

        private double pingsReceived() {
            return counter("jburrow.frames", "type", "ping", "direction", "received");
        }
    }
}
