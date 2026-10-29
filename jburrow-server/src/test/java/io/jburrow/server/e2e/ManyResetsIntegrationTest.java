package io.jburrow.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.core.tunnel.application.StreamQuota;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Receiving and §Security and threat model: credit doesn't leak
 * through resets. 10,000 streams, 100 at a time, each reset by its visitor partway through a download, with more of it
 * in flight in both windows. Then one visitor downloads sixteen of the server's session windows at full speed: had any
 * reset kept session credit, the session would stall for good once enough had leaked.
 *
 * <p>Leak detection samples while it runs ({@link SampledLeakDetection}): the resets move gigabytes in small frames.
 */
@TestPropertySource(properties = "jburrow.tcp.port-range=31900-31949")
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
@ExtendWith(FlowStateDump.class)
class ManyResetsIntegrationTest extends TunnelTestSupport {

    private static final int STREAMS = 10_000;
    private static final int AT_ONCE = 100;
    /** More than the server's stream window, so the client is held back on it when the reset comes. */
    private static final int OFFERED = 300 * 1024;

    private static final int READ_BEFORE_RESET = 100 * 1024;
    private static final long RESET_SEED = 300;
    private static final Duration RESETS_DEADLINE = Duration.ofMinutes(4);

    private static final long FINAL = 8L * 1024 * 1024;
    private static final long FINAL_SEED = 8;
    private static final Duration FINAL_DEADLINE = Duration.ofSeconds(10);

    @Autowired
    StreamQuota streamQuota;

    @Test
    void tenThousandResetsMidTransferLeaveTheSessionsCreditWhole() throws Exception {
        closeAfterTest(new SampledLeakDetection());
        LocalService source = closeAfterTest(LocalService.source());
        Tunnel tunnel = openTunnel(source);
        byte[] expected = firstBytes(OFFERED, RESET_SEED, READ_BEFORE_RESET);
        double resetsBefore = visitorResets();

        AtomicInteger next = new AtomicInteger();
        List<CompletableFuture<Void>> workers = new ArrayList<>();
        long started = System.nanoTime();
        for (int i = 0; i < AT_ONCE; i++) {
            workers.add(async(() -> {
                while (next.getAndIncrement() < STREAMS) {
                    resetMidTransfer(tunnel.port(), expected);
                }
                return null;
            }));
        }
        awaitAll(workers, RESETS_DEADLINE);
        System.out.printf(
                "%d streams reset mid-transfer in %d ms%n",
                STREAMS, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        awaitNoStreams();
        await("the stream quota is back to zero", () -> streamQuota.inUse() == 0);
        assertThat(visitorResets() - resetsBefore)
                .as("streams the visitors reset")
                .isEqualTo(STREAMS);

        Socket visitor = visit(tunnel.port());
        long downloadStarted = System.nanoTime();
        LocalService.request(visitor.getOutputStream(), FINAL, FINAL_SEED);
        Received downloaded = drain(visitor.getInputStream());
        Duration took = Duration.ofNanos(System.nanoTime() - downloadStarted);

        assertThat(downloaded).isEqualTo(new Received(FINAL, expectedDigest(FINAL, FINAL_SEED)));
        assertThat(took).as("the download after the resets").isLessThan(FINAL_DEADLINE);
        assertOpen(tunnel.handle());
    }

    /** Asks for {@link #OFFERED} bytes, checks the first {@link #READ_BEFORE_RESET}, then resets the connection. */
    private static void resetMidTransfer(int port, byte[] expected) throws IOException {
        try (Socket visitor = new Socket()) {
            visitor.setTcpNoDelay(true);
            visitor.setSoTimeout((int) TIMEOUT.toMillis());
            visitor.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), (int) TIMEOUT.toMillis());
            LocalService.request(visitor.getOutputStream(), OFFERED, RESET_SEED);
            assertThat(visitor.getInputStream().readNBytes(READ_BEFORE_RESET)).isEqualTo(expected);
            visitor.setSoLinger(true, 0);
        }
    }

    private double visitorResets() {
        return counter("jburrow.stream.resets", "code", "PEER_RESET", "side", "local");
    }

    private static byte[] firstBytes(int length, long seed, int prefix) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(length);
        send(bytes, length, seed);
        return Arrays.copyOf(bytes.toByteArray(), prefix);
    }
}
