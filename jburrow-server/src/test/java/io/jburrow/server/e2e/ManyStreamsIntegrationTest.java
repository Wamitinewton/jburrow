package io.jburrow.server.e2e;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Testing: 500 visitors at once on one session, each sending 1 MiB
 * of its own random bytes through an echo service and reading every byte back. On both transports, since it is about
 * the server's read path.
 *
 * <p>Every visitor connects before any sends, so all 500 streams are open together; the session's closing line then
 * says how many it served and the most it held at once. None of the payload reaches the log. Leak detection samples
 * while it runs ({@link SampledLeakDetection}), so the time bound measures the tunnel.
 */
class ManyStreamsIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(properties = {"jburrow.tcp.port-range=31600-31649", "jburrow.edge.transport=epoll"})
    class OnEpoll extends Scenario {}

    @Nested
    @TestPropertySource(properties = {"jburrow.tcp.port-range=31650-31699", "jburrow.edge.transport=nio"})
    class OnNio extends Scenario {}

    @Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @ExtendWith({OutputCaptureExtension.class, FlowStateDump.class})
    abstract static class Scenario extends TunnelTestSupport {

        private static final int VISITORS = 500;
        private static final long EACH = 1024 * 1024;
        private static final long FIRST_SEED = 7_000;
        private static final Duration DEADLINE = Duration.ofSeconds(60);
        /** They overlap rather than all being open at every instant, so the peak may fall a little short. */
        private static final int LEAST_PEAK = 450;

        private static final int NEEDLE_LENGTH = 16;
        private static final Pattern STREAMS = Pattern.compile(" streams=(\\d+) ");
        private static final Pattern MAX_STREAMS = Pattern.compile(" maxStreams=(\\d+) ");

        @Test
        void fiveHundredVisitorsShareOneSessionAndEveryByteComesBack(CapturedOutput output) throws Exception {
            closeAfterTest(new SampledLeakDetection());
            LocalService echo = closeAfterTest(LocalService.echo());
            Tunnel tunnel = openTunnel(echo);

            List<CompletableFuture<Socket>> connecting = new ArrayList<>();
            for (int i = 0; i < VISITORS; i++) {
                connecting.add(async(() -> visit(tunnel.port())));
            }
            List<Socket> visitors = awaitAll(connecting, TIMEOUT);
            await("every visitor has a stream", () -> gauge("jburrow.streams.active", "kind", "tcp") == VISITORS);

            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<Void>> transfers = new ArrayList<>();
            for (int i = 0; i < VISITORS; i++) {
                Socket visitor = visitors.get(i);
                long seed = FIRST_SEED + i;
                transfers.add(async(() -> echoThrough(visitor, seed, go)));
            }
            long started = System.nanoTime();
            go.countDown();
            awaitAll(transfers, DEADLINE);
            System.out.printf(
                    "%d streams of %d bytes each way in %d ms on %s%n",
                    VISITORS, EACH, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), edge.transportName());

            String sessionId = tunnel.handle().sessionId();
            tunnel.handle().close();
            tunnel.awaitClosed(TIMEOUT);
            await("the session's closing is logged", () -> sessionClosedLines(output, sessionId) == 1);
            String closed = output.getAll()
                    .lines()
                    .filter(line -> line.contains("session closed: session=" + sessionId))
                    .findFirst()
                    .orElseThrow();
            assertThat(field(STREAMS, closed)).as("streams served").isEqualTo(VISITORS);
            assertThat(field(MAX_STREAMS, closed))
                    .as("most streams open at once")
                    .isBetween(LEAST_PEAK, VISITORS);

            assertNoPayloadIn(output.getAll());
        }

        /** Sends {@link #EACH} bytes from {@code seed} once {@code go} opens, while reading the echo to its end. */
        private Void echoThrough(Socket visitor, long seed, CountDownLatch go) throws Exception {
            visitor.setSoTimeout((int) DEADLINE.toMillis());
            go.await();
            CompletableFuture<String> sent = async(() -> {
                String digest = send(visitor.getOutputStream(), EACH, seed);
                visitor.shutdownOutput();
                return digest;
            });
            Received echoed = drain(visitor.getInputStream());
            assertThat(echoed)
                    .as("the echo of seed %d", seed)
                    .isEqualTo(new Received(EACH, sent.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)));
            return null;
        }

        /** Each payload's first bytes, raw, in hex and in Base64: none may appear in the log. */
        private static void assertNoPayloadIn(String log) {
            for (int i = 0; i < VISITORS; i++) {
                byte[] needle = payloadPrefix(FIRST_SEED + i, NEEDLE_LENGTH);
                assertThat(log)
                        .doesNotContain(new String(needle, ISO_8859_1))
                        .doesNotContainIgnoringCase(HexFormat.of().formatHex(needle))
                        .doesNotContain(Base64.getEncoder().encodeToString(needle));
            }
        }

        private static long sessionClosedLines(CapturedOutput output, String sessionId) {
            return output.getAll()
                    .lines()
                    .filter(line -> line.contains("session closed: session=" + sessionId))
                    .count();
        }

        private static int field(Pattern pattern, String line) {
            Matcher matcher = pattern.matcher(line);
            assertThat(matcher.find()).as("%s in %s", pattern, line).isTrue();
            return Integer.parseInt(matcher.group(1));
        }
    }
}
