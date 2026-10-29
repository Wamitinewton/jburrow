package io.jburrow.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.wire.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.TestPropertySource;

/**
 * Phase 2's exit test in CI ({@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Testing, §Memory budget): 500 streams
 * on one session whose client is held to 2 MiB/s each way by a {@link ThrottlingProxy}. Visitors send faster than the
 * session can carry, then a local service does, and in both directions the memory the server holds stays within its
 * per-session budget and doesn't grow with the bytes carried. On both transports, since it is about the server's read
 * path.
 *
 * <p>Each stream carries {@link #EACH} bytes, so a direction is 62.5 MiB, about half a minute at the throttle: far more
 * than any buffer on the path, and short enough that both directions on both transports stay within five minutes. The
 * stream count is what the exit criterion names, so it is never the one reduced.
 *
 * <p>Direct bytes held are sampled every {@link #SAMPLE_INTERVAL}, and their most must stay within {@link #DIRECT_BOUND}:
 * the server's per-session budget (≈ 0.9 MiB) plus the client's, which runs in this JVM (its 1 MiB session window, its
 * 256 KiB control channel buffer and a frame), with room for the decoders and the allocator's granularity. Buffering
 * per stream would need 500 × 256 KiB = 125 MiB.
 */
class SlowClientIntegrationTest {

    @Nested
    @EnabledIf("io.netty.channel.epoll.Epoll#isAvailable")
    @TestPropertySource(properties = {"jburrow.tcp.port-range=31800-31849", "jburrow.edge.transport=epoll"})
    class OnEpoll extends Scenario {}

    @Nested
    @TestPropertySource(properties = {"jburrow.tcp.port-range=31850-31899", "jburrow.edge.transport=nio"})
    class OnNio extends Scenario {}

    @Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @ExtendWith(FlowStateDump.class)
    abstract static class Scenario extends TunnelTestSupport {

        private static final int VISITORS = 500;
        private static final long EACH = 128 * 1024;
        private static final long TOTAL = VISITORS * EACH;
        private static final long FIRST_SEED = 9_000;
        private static final long BYTES_PER_SECOND = 2 * 1024 * 1024;
        private static final Duration DEADLINE = Duration.ofMinutes(2);

        private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(2);
        private static final long DIRECT_BOUND = 4 * 1024 * 1024;
        /** The streams' objects all exist by the first quarter; nothing may grow with the bytes carried after it. */
        private static final long HEAP_GROWTH_BOUND = 16 * 1024 * 1024;

        @Test
        void visitorsSendingFasterThanTheClientTakesLeaveTheServersMemoryFlat() throws Exception {
            Map<Long, Received> received = new ConcurrentHashMap<>();
            AtomicLong progress = new AtomicLong();
            LocalService sink = closeAfterTest(LocalService.sink(received, progress));
            Tunnel tunnel = openThrottledTunnel(sink);
            ProtocolErrors errorsBefore = protocolErrors();
            List<Socket> visitors = connectVisitors(tunnel);

            try (MemoryProbe memory = MemoryProbe.sampleEvery(SAMPLE_INTERVAL)) {
                List<CompletableFuture<Void>> transfers = new ArrayList<>();
                for (int i = 0; i < VISITORS; i++) {
                    Socket visitor = visitors.get(i);
                    long seed = FIRST_SEED + i;
                    transfers.add(async(() -> upload(visitor, seed)));
                }
                long heapAtQuarter = heapAfterGcOnceDelivered(progress, TOTAL / 4);
                awaitAll(transfers, DEADLINE);
                await("the sink has recorded every stream", () -> received.size() == VISITORS);
                assertMemoryFlat("visitors to client", memory, heapAtQuarter);
            }

            for (int i = 0; i < VISITORS; i++) {
                long seed = FIRST_SEED + i;
                assertThat(received.get(seed))
                        .as("what the sink received from seed %d", seed)
                        .isEqualTo(new Received(EACH, expectedDigest(EACH, seed)));
            }
            assertThat(protocolErrors()).isEqualTo(errorsBefore);
            assertOpen(tunnel.handle());
        }

        @Test
        void aLocalServiceSendingFasterThanTheClientUploadsLeavesTheServersMemoryFlat() throws Exception {
            LocalService source = closeAfterTest(LocalService.source());
            Tunnel tunnel = openThrottledTunnel(source);
            ProtocolErrors errorsBefore = protocolErrors();
            List<Socket> visitors = connectVisitors(tunnel);
            AtomicLong progress = new AtomicLong();

            List<Received> downloaded;
            try (MemoryProbe memory = MemoryProbe.sampleEvery(SAMPLE_INTERVAL)) {
                List<CompletableFuture<Received>> transfers = new ArrayList<>();
                for (int i = 0; i < VISITORS; i++) {
                    Socket visitor = visitors.get(i);
                    long seed = FIRST_SEED + i;
                    transfers.add(async(() -> download(visitor, seed, progress)));
                }
                long heapAtQuarter = heapAfterGcOnceDelivered(progress, TOTAL / 4);
                downloaded = awaitAll(transfers, DEADLINE);
                assertMemoryFlat("client to visitors", memory, heapAtQuarter);
            }

            for (int i = 0; i < VISITORS; i++) {
                long seed = FIRST_SEED + i;
                assertThat(downloaded.get(i))
                        .as("what the visitor of seed %d downloaded", seed)
                        .isEqualTo(new Received(EACH, expectedDigest(EACH, seed)));
            }
            assertThat(protocolErrors()).isEqualTo(errorsBefore);
            assertOpen(tunnel.handle());
        }

        private Tunnel openThrottledTunnel(LocalService service) throws Exception {
            ThrottlingProxy proxy = closeAfterTest(ThrottlingProxy.start(edge.boundPort(), BYTES_PER_SECOND));
            return openTunnel(client(ALICE_TOKEN, tcp(service.port())).server(connectUriThrough(proxy)));
        }

        private List<Socket> connectVisitors(Tunnel tunnel) throws Exception {
            List<CompletableFuture<Socket>> connecting = new ArrayList<>();
            for (int i = 0; i < VISITORS; i++) {
                connecting.add(async(() -> {
                    Socket visitor = visit(tunnel.port());
                    visitor.setSoTimeout((int) DEADLINE.toMillis());
                    return visitor;
                }));
            }
            List<Socket> visitors = awaitAll(connecting, TIMEOUT);
            await("every visitor has a stream", () -> gauge("jburrow.streams.active", "kind", "tcp") == VISITORS);
            return visitors;
        }

        /** Sends the sink this visitor's id and bytes, then waits for the sink to close the stream. */
        private static Void upload(Socket visitor, long seed) throws IOException {
            LocalService.writeId(visitor.getOutputStream(), seed);
            send(visitor.getOutputStream(), EACH, seed);
            visitor.shutdownOutput();
            assertThat(visitor.getInputStream().read()).isEqualTo(-1);
            return null;
        }

        /** Asks the source for this visitor's bytes and reads them at full speed. */
        private static Received download(Socket visitor, long seed, AtomicLong progress) throws IOException {
            LocalService.request(visitor.getOutputStream(), EACH, seed);
            InputStream in = visitor.getInputStream();
            Received downloaded = drain(in, progress);
            visitor.shutdownOutput();
            return downloaded;
        }

        private static long heapAfterGcOnceDelivered(AtomicLong progress, long bytes) throws InterruptedException {
            long deadline = System.nanoTime() + DEADLINE.toNanos();
            while (progress.get() < bytes) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError(
                            "Only " + progress.get() + " of " + bytes + " bytes arrived in " + DEADLINE);
                }
                Thread.sleep(100);
            }
            return MemoryProbe.heapAfterGc();
        }

        private void assertMemoryFlat(String direction, MemoryProbe memory, long heapAtQuarter)
                throws InterruptedException {
            long heapAtEnd = MemoryProbe.heapAfterGc();
            long mostDirect = memory.mostDirectGrowth();
            System.out.printf(
                    "Slow client on %s, %s: most direct bytes held %d KiB, heap after GC %d MiB at the first quarter"
                            + " and %d MiB at the end%n",
                    edge.transportName(), direction, mostDirect / 1024, heapAtQuarter >> 20, heapAtEnd >> 20);
            assertThat(mostDirect).as("most direct bytes held, %s", direction).isLessThanOrEqualTo(DIRECT_BOUND);
            assertThat(heapAtEnd)
                    .as("heap after GC at the end, %s", direction)
                    .isLessThanOrEqualTo(heapAtQuarter + HEAP_GROWTH_BOUND);
        }

        /** The session errors that must not happen, raised by either side. */
        private record ProtocolErrors(double flowControl, double heartbeat) {}

        private ProtocolErrors protocolErrors() {
            double flowControl = 0;
            double heartbeat = 0;
            for (String side : List.of("local", "remote")) {
                flowControl +=
                        counter("jburrow.protocol.errors", "code", ErrorCode.FLOW_CONTROL_ERROR.code(), "side", side);
                heartbeat +=
                        counter("jburrow.protocol.errors", "code", ErrorCode.HEARTBEAT_TIMEOUT.code(), "side", side);
            }
            return new ProtocolErrors(flowControl, heartbeat);
        }
    }
}
