package io.jburrow.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.testsupport.LeakRecordingFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * A fast sender and a slow reader on either end of a tunnel: memory stays within the per-session budget of {@code
 * docs/phase-2-multiplexing/ARCHITECTURE.md} §Memory budget, and the session survives a stall longer than the idle
 * timeout, since neither side stops reading the control connection for flow control: the slow reader's stream credit
 * is withheld instead.
 *
 * <p>The reader reads 64 KiB per 100 ms for {@link #SLOW_PHASE}, long enough to fill every buffer on the path and hold
 * the sender back, then reads at full speed so the whole 256 MiB arrives and is checked. Direct memory is sampled every
 * 50 ms throughout.
 */
@TestPropertySource(
        properties = {
            "jburrow.tcp.port-range=31100-31199",
            "jburrow.edge.ping-interval=1s",
            "jburrow.edge.idle-timeout=2s"
        })
class SlowReaderIntegrationTest extends TunnelTestSupport {

    private static final long TOTAL = 256L * 1024 * 1024;
    private static final int SLOW_READ = 64 * 1024;
    private static final Duration SLOW_PAUSE = Duration.ofMillis(100);
    /** Twice the idle timeout: a stall that long ends the session unless the paused side keeps pinging. */
    private static final Duration SLOW_PHASE = Duration.ofSeconds(4);

    private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(50);
    private static final Duration TRANSFER_TIMEOUT = Duration.ofSeconds(60);

    /**
     * One session's worst case, ≈ 0.9 MiB, rounded up to 1 MiB, for each end: the client runs in this JVM, with the
     * same rules and the same allocator.
     */
    private static final long HELD_BUDGET = 2 * 1024 * 1024;

    /**
     * The gauge counts the chunks the allocator reserves rather than the bytes buffers hold, so it moves in steps of a
     * chunk and keeps chunks it may reuse. A bound far below the transfer still shows it stays flat.
     */
    private static final long GAUGE_BOUND = 32 * 1024 * 1024;

    @Test
    void aSlowLocalServiceHoldsBackAFastVisitor() throws Exception {
        CompletableFuture<Received> received = new CompletableFuture<>();
        LocalService slowSink = closeAfterTest(
                LocalService.start(connection -> received.complete(slowThenFast(connection.getInputStream()))));
        Tunnel tunnel = openTunnel(slowSink);
        double blockedBefore = counter("jburrow.flow.blocked", "scope", "stream");

        Socket visitor = visit(tunnel.port());
        try (DirectMemorySampler sampler = new DirectMemorySampler()) {
            CompletableFuture<String> sent = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            String digest = send(visitor.getOutputStream(), TOTAL, 3);
                            visitor.shutdownOutput();
                            return digest;
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    },
                    task -> Thread.ofVirtual().start(task));
            Received arrived = received.get(TRANSFER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(arrived).isEqualTo(new Received(TOTAL, sent.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)));
            sampler.assertWithinBudget();
        }

        assertOpen(tunnel.handle());
        assertThat(counter("jburrow.flow.blocked", "scope", "stream"))
                .as("the visitor waited for its own stream's credit")
                .isGreaterThan(blockedBefore);
    }

    @Test
    void aSlowVisitorHoldsBackAFastLocalService() throws Exception {
        LocalService fastSource = closeAfterTest(LocalService.start(connection -> {
            send(connection.getOutputStream(), TOTAL, 4);
            connection.shutdownOutput();
            drain(connection.getInputStream());
        }));
        Tunnel tunnel = openTunnel(fastSource);
        double pausesBefore = counter("jburrow.backpressure.pauses", "direction", "outbound");

        Socket visitor = visit(tunnel.port());
        visitor.setSoTimeout((int) TRANSFER_TIMEOUT.toMillis());
        try (DirectMemorySampler sampler = new DirectMemorySampler()) {
            Received arrived = slowThenFast(visitor.getInputStream());
            assertThat(arrived).isEqualTo(new Received(TOTAL, expectedDigest(TOTAL, 4)));
            sampler.assertWithinBudget();
        }
        visitor.shutdownOutput();

        assertOpen(tunnel.handle());
        assertThat(counter("jburrow.backpressure.pauses", "direction", "outbound"))
                .as("the server never stops reading the client")
                .isEqualTo(pausesBefore);
    }

    /** Reads 64 KiB per 100 ms for {@link #SLOW_PHASE} from the first byte, then to the end at full speed. */
    private static Received slowThenFast(InputStream in) throws IOException {
        MessageDigest sha256 = sha256();
        byte[] buffer = new byte[SLOW_READ];
        long length = 0;
        long slowUntil = 0;
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            sha256.update(buffer, 0, read);
            length += read;
            if (slowUntil == 0) {
                slowUntil = System.nanoTime() + SLOW_PHASE.toNanos();
            }
            if (System.nanoTime() < slowUntil) {
                sleep(SLOW_PAUSE);
            }
        }
        return new Received(length, HexFormat.of().formatHex(sha256.digest()));
    }

    private static void sleep(Duration pause) throws IOException {
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /**
     * Samples, from construction until closed, the direct memory live buffers hold and the edge's own gauge, each
     * against its reading at construction.
     */
    private final class DirectMemorySampler implements AutoCloseable {

        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        private final long heldBaseline = LeakRecordingFactory.heldDirectBytes();
        private final long gaugeBaseline = edge.usedDirectMemory();
        private final AtomicLong maxHeld = new AtomicLong(heldBaseline);
        private final AtomicLong maxGauge = new AtomicLong(gaugeBaseline);

        DirectMemorySampler() {
            assertThat(gaugeBaseline).as("the edge measures its direct memory").isNotNegative();
            timer.scheduleAtFixedRate(
                    () -> {
                        maxHeld.accumulateAndGet(LeakRecordingFactory.heldDirectBytes(), Math::max);
                        maxGauge.accumulateAndGet(edge.usedDirectMemory(), Math::max);
                    },
                    0,
                    SAMPLE_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            timer.shutdownNow();
        }

        void assertWithinBudget() {
            assertThat(maxHeld.get() - heldBaseline)
                    .as("growth of the direct memory buffers hold, both ends of the tunnel")
                    .isLessThanOrEqualTo(HELD_BUDGET);
            assertThat(maxGauge.get() - gaugeBaseline)
                    .as("growth of jburrow_netty_direct_memory_bytes")
                    .isLessThanOrEqualTo(GAUGE_BOUND);
        }
    }
}
