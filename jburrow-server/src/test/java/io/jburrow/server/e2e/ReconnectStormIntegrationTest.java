package io.jburrow.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.ReconnectPolicy;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.jburrow.server.e2e.RecordingEvents.Disconnected;
import io.jburrow.server.e2e.RecordingEvents.Entry;
import io.jburrow.server.e2e.support.ServerContexts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.TestPropertySource;

/**
 * A deploy under 200 tunnels ({@code docs/phase-4-staying-connected/ARCHITECTURE.md} §Memory budget, §Security and
 * threat model): every client comes back with its URL, the new process never has more handshakes in progress than its
 * cap, and no client tries again sooner than its own schedule said it would. The cap is 16 here, so the storm meets it;
 * clients refused at it are told to come back later and do.
 *
 * <p>The handshakes in progress are sampled continuously from the new process's admission, from before the first
 * client reaches it until the last is back. Each client's attempts are read from its {@code TunnelEvents}: every
 * {@code onDisconnected} names the delay before the next attempt, and whatever the client reports next must come at
 * least that much later. After the session's own end, which follows {@code GOAWAY}'s spread, no delay of the schedule
 * is under half a second.
 */
class ReconnectStormIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(properties = {"jburrow.tcp.port-range=32160-32169", "jburrow.edge.transport=epoll"})
    class OnEpoll extends Scenario {
        OnEpoll() {
            super(new ServerContexts.Ports(32_170), ON_EPOLL);
        }
    }

    @Nested
    @TestPropertySource(properties = {"jburrow.tcp.port-range=32190-32199", "jburrow.edge.transport=nio"})
    class OnNio extends Scenario {
        OnNio() {
            super(new ServerContexts.Ports(32_200), ON_NIO);
        }
    }

    @Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    abstract static class Scenario extends TunnelTestSupport {

        static final int CLIENTS = 200;
        static final int MAX_HANDSHAKING = 16;
        /** Several rounds of refusals at the cap, each told to wait {@code Retry-After}. */
        static final Duration ALL_BACK_WITHIN = Duration.ofSeconds(90);
        /** The least delay the schedule gives once a run has started: half a unit, the backoff's floor. */
        static final Duration LEAST_LATER_DELAY = Duration.ofMillis(500);
        /** Far shorter than a handshake on loopback, so none goes unseen for long. */
        static final Duration SAMPLE_EVERY = Duration.ofNanos(50_000);

        private final ServerContexts.Ports ports;
        private final String transport;

        Scenario(ServerContexts.Ports ports, String transport) {
            this.ports = ports;
            this.transport = transport;
        }

        @Test
        void everyClientComesBackWithItsUrlWithinTheHandshakeCapAndItsSchedule() throws Exception {
            ServerContexts servers = serverContexts(
                    ports,
                    transport,
                    "jburrow.edge.max-handshaking=" + MAX_HANDSHAKING,
                    "jburrow.edge.max-sessions=" + (CLIENTS + 50),
                    "jburrow.edge.max-sessions-per-principal=" + CLIENTS);
            servers.start();
            List<Tunnel> tunnels = new ArrayList<>(CLIENTS);
            for (int i = 0; i < CLIENTS; i++) {
                tunnels.add(openTunnel(httpClient(ALICE_TOKEN, unusedPort())
                        .server(servers.connectUri())
                        .reconnect(ReconnectPolicy.standard())));
            }

            servers.stop();

            AtomicInteger mostHandshaking = new AtomicInteger();
            AtomicBoolean sampling = new AtomicBoolean(true);
            servers.start(direct -> {
                SessionAdmission admission = servers.running().getBean(SessionAdmission.class);
                Thread.ofPlatform().daemon().name("handshake-sampler").start(() -> {
                    while (sampling.get()) {
                        mostHandshaking.accumulateAndGet(admission.handshaking(), Math::max);
                        LockSupport.parkNanos(SAMPLE_EVERY.toNanos());
                    }
                });
            });
            try {
                long deadline = System.nanoTime() + ALL_BACK_WITHIN.toNanos();
                for (Tunnel tunnel : tunnels) {
                    Duration left = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
                    assertThat(tunnel.events().awaitReconnected(left).sameUrls())
                            .as("client %s is back with its URL", tunnel.host())
                            .isTrue();
                }
            } finally {
                sampling.set(false);
            }

            assertThat(mostHandshaking.get()).isLessThanOrEqualTo(MAX_HANDSHAKING);
            for (Tunnel tunnel : tunnels) {
                assertThat(tunnel.handle().tunnels().getFirst().publicHost()).isEqualTo(tunnel.host());
                assertKeptToItsSchedule(tunnel.events());
            }
        }

        private static void assertKeptToItsSchedule(RecordingEvents events) {
            List<Entry> timeline = events.timeline().stream()
                    .filter(entry ->
                            entry.kind().equals("disconnected") || entry.kind().equals("reconnected"))
                    .toList();
            List<Disconnected> disconnects = events.disconnects();
            assertThat(timeline.getLast().kind()).isEqualTo("reconnected");
            assertThat(timeline).hasSize(disconnects.size() + 1);
            for (int i = 0; i < disconnects.size(); i++) {
                Disconnected disconnect = disconnects.get(i);
                Duration untilNext = Duration.ofNanos(timeline.get(i + 1).atNanos() - disconnect.atNanos());
                assertThat(untilNext)
                        .as("report %d, which asked for %s", i + 1, disconnect.retryIn())
                        .isGreaterThanOrEqualTo(disconnect.retryIn());
                if (i > 0) {
                    assertThat(disconnect.retryIn()).as("delay %d", i + 1).isGreaterThanOrEqualTo(LEAST_LATER_DELAY);
                }
            }
        }
    }
}
