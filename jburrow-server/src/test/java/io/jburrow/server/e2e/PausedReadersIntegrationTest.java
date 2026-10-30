package io.jburrow.server.e2e;

import static io.jburrow.server.e2e.TunnelTestSupport.LOOPBACK_IS_THE_INGRESS;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_EPOLL;
import static io.jburrow.server.e2e.TunnelTestSupport.ON_NIO;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.e2e.support.HttpVisitor;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.TestPropertySource;

/**
 * Paused readers ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Paused readers), end to end: visitors that start
 * a large download and stop reading, as a browser does with a paused video, beside a probe that keeps asking for a
 * small page on the same tunnel.
 *
 * <p>With parking, the stalled responses are taken out of the session window and the probe never notices them. With
 * parking off, phase 2's limit holds: the server's session window is two stream windows, so the second stalled
 * response stops every other response on the tunnel.
 *
 * <p>Edge connections get production's 16 KiB socket buffers, so a stalled response fills the kernel's buffers in a
 * moment and then holds its stream's window, as phase 2's arithmetic assumes. Ten stalled windows are 2.5 MiB, over the
 * default per-session parking budget of 2 MiB, which is that budget doing its job; the test raises it to 4 MiB so all
 * ten fit, as the architecture's scenario assumes.
 */
class PausedReadersIntegrationTest {

    private static final String PRODUCTION_SOCKET_BUFFERS = "jburrow.http.socket-buffer=16KB";

    private static final String TEN_WINDOWS_PER_SESSION = "jburrow.http.max-parked-per-session=4MB";
    private static final String PARKING_OFF = "jburrow.http.max-parked=0";
    private static final String NOTHING_PER_SESSION = "jburrow.http.max-parked-per-session=0";

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(
            properties = {
                "jburrow.tcp.port-range=30920-30929",
                ON_EPOLL,
                LOOPBACK_IS_THE_INGRESS,
                PRODUCTION_SOCKET_BUFFERS,
                TEN_WINDOWS_PER_SESSION
            })
    class WithParkingOnEpoll extends WithParking {}

    @Nested
    @TestPropertySource(
            properties = {
                "jburrow.tcp.port-range=30930-30939",
                ON_NIO,
                LOOPBACK_IS_THE_INGRESS,
                PRODUCTION_SOCKET_BUFFERS,
                TEN_WINDOWS_PER_SESSION
            })
    class WithParkingOnNio extends WithParking {}

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(
            properties = {
                "jburrow.tcp.port-range=30940-30949",
                ON_EPOLL,
                LOOPBACK_IS_THE_INGRESS,
                PRODUCTION_SOCKET_BUFFERS,
                PARKING_OFF,
                NOTHING_PER_SESSION
            })
    class WithoutParkingOnEpoll extends WithoutParking {}

    @Nested
    @TestPropertySource(
            properties = {
                "jburrow.tcp.port-range=30950-30959",
                ON_NIO,
                LOOPBACK_IS_THE_INGRESS,
                PRODUCTION_SOCKET_BUFFERS,
                PARKING_OFF,
                NOTHING_PER_SESSION
            })
    class WithoutParkingOnNio extends WithoutParking {}

    abstract static class WithParking extends Stalls {

        private static final Duration PROBING = Duration.ofSeconds(30);
        private static final Duration PROBE_INTERVAL = Duration.ofMillis(100);
        private static final Duration P99_BOUND = Duration.ofMillis(100);
        /** Twice {@code jburrow.http.park-after}. */
        private static final Duration STILL = Duration.ofSeconds(2);

        private static final Duration SETTLING = Duration.ofSeconds(30);

        @Test
        void aProbeBesideTenStalledResponsesStaysFast() throws Exception {
            Tunnel tunnel = openTunnelToService();
            double parkedBefore = counter("jburrow.flow.parked");

            stall(tunnel, STALLED);
            awaitParkingSettled();
            assertThat(counter("jburrow.flow.parked") - parkedBefore).isGreaterThanOrEqualTo(STALLED);
            assertThat(gauge("jburrow.flow.parked.bytes")).isPositive();

            HttpVisitor probe = ingressConnection();
            List<Long> latencies = new ArrayList<>();
            long until = System.nanoTime() + PROBING.toNanos();
            while (System.nanoTime() < until) {
                long started = System.nanoTime();
                probe.request("GET", tunnel.host(), "/hello");
                assertThat(probe.response().text()).isEqualTo("hello\n");
                latencies.add(System.nanoTime() - started);
                Thread.sleep(PROBE_INTERVAL);
            }

            Duration p99 = Duration.ofNanos(percentile(latencies, 0.99));
            System.out.printf(
                    "Paused readers on %s with parking: %d probes, p99 %d ms, %d KiB parked%n",
                    edge.transportName(),
                    latencies.size(),
                    p99.toMillis(),
                    (long) gauge("jburrow.flow.parked.bytes") / 1024);
            assertThat(p99).as("probe p99 beside %d stalled responses", STALLED).isLessThan(P99_BOUND);
            assertThat(counter("jburrow.flow.park.refused")).isZero();

            closeStallers();
            await("nothing is parked once the stalled visitors leave", () -> gauge("jburrow.flow.parked.bytes") == 0);
        }

        /**
         * Waits until the parked total has held still for longer than {@code park-after}: each stalled response has
         * filled its buffers, held its stream's window and been parked. Until then a response still filling up holds
         * session credit for up to {@code park-after}, the delay parking bounds rather than removes.
         */
        private void awaitParkingSettled() throws InterruptedException {
            long deadline = System.nanoTime() + SETTLING.toNanos();
            double parked = -1;
            long stillSince = System.nanoTime();
            while (parked <= 0 || System.nanoTime() - stillSince < STILL.toNanos()) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError(
                            "Parking didn't settle within " + SETTLING + "; " + parked + " bytes parked");
                }
                double now = gauge("jburrow.flow.parked.bytes");
                if (now != parked) {
                    parked = now;
                    stillSince = System.nanoTime();
                }
                Thread.sleep(50);
            }
        }

        private static long percentile(List<Long> values, double fraction) {
            List<Long> sorted = values.stream().sorted().toList();
            return sorted.get((int) Math.ceil(fraction * sorted.size()) - 1);
        }
    }

    abstract static class WithoutParking extends Stalls {

        /** A probe that waits this long for its six bytes is stuck behind the stalled responses, not slow. */
        private static final Duration STUCK = Duration.ofSeconds(2);
        /** Long enough for a stalled response to fill its socket's buffers and its stream's window on loopback. */
        private static final Duration SETTLE = Duration.ofSeconds(1);

        @Test
        void theSecondStalledResponseStopsEveryOtherResponseOnTheTunnel() throws Exception {
            Tunnel tunnel = openTunnelToService();

            OptionalInt stuckAfter = OptionalInt.empty();
            for (int stalled = 1; stalled <= STALLED && stuckAfter.isEmpty(); stalled++) {
                stall(tunnel, 1);
                Thread.sleep(SETTLE);
                if (!probeAnswers(tunnel)) {
                    stuckAfter = OptionalInt.of(stalled);
                }
            }

            assertThat(stuckAfter)
                    .as("the stalled response after which a probe no longer completes")
                    .hasValue(2);
            assertThat(gauge("jburrow.flow.parked.bytes")).isZero();

            closeStallers();
            assertThat(probeAnswers(tunnel))
                    .as("a probe once the stalled visitors have left")
                    .isTrue();
        }

        private boolean probeAnswers(Tunnel tunnel) throws IOException {
            HttpVisitor probe = ingressConnection();
            probe.timeout(STUCK);
            probe.request("GET", tunnel.host(), "/hello");
            try {
                return probe.response().text().equals("hello\n");
            } catch (SocketTimeoutException stuck) {
                return false;
            }
        }
    }

    @Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @ExtendWith(FlowStateDump.class)
    abstract static class Stalls extends TunnelTestSupport {

        static final int STALLED = 10;

        /** A browser's receive buffer once it has stopped reading: small, so the stall reaches the edge quickly. */
        private static final int PAUSED_RECEIVE_BUFFER = 4 * 1024;

        private final List<HttpVisitor> stallers = new ArrayList<>();

        Tunnel openTunnelToService() throws Exception {
            LocalHttpService service = closeAfterTest(LocalHttpService.start());
            return openHttpTunnel(ALICE_TOKEN, service.port());
        }

        /** {@code count} visitors ask for {@code /stall}'s 64 MiB and read none of it. */
        void stall(Tunnel tunnel, int count) throws IOException {
            for (int i = 0; i < count; i++) {
                HttpVisitor staller = closeAfterTest(
                                HttpVisitor.connect(null, edge.boundPort(), PAUSED_RECEIVE_BUFFER, TIMEOUT))
                        .asIngress();
                staller.request("GET", tunnel.host(), "/stall");
                stallers.add(staller);
            }
        }

        void closeStallers() throws Exception {
            for (HttpVisitor staller : stallers) {
                staller.close();
            }
            stallers.clear();
            awaitNoExchanges();
        }
    }
}
