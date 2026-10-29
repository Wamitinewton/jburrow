package io.jburrow.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Head-of-line blocking and deadlock: a stalled bulk download and an
 * interactive stream on one session. A session has one tunnel in phase 2, so both visitors reach the same local
 * service, which streams 64 MiB to a connection that asks for it with {@link LocalService#BULK} and echoes any other.
 *
 * <p>Visitor A asks for the bulk and doesn't read, so its stream's window fills and the service stops partway. Visitor
 * B then makes a thousand round trips through the same session, each of which must be quick; a stall would last as long
 * as A's 64 MiB. Then A reads everything, intact.
 */
@TestPropertySource(properties = "jburrow.tcp.port-range=31700-31749")
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
@ExtendWith(FlowStateDump.class)
class HeadOfLineIntegrationTest extends TunnelTestSupport {

    private static final long BULK_BYTES = 64L * 1024 * 1024;
    private static final long BULK_SEED = 64;
    private static final int ROUND_TRIPS = 1_000;
    private static final int MESSAGE = 64;
    /** Locally a round trip takes about a millisecond; the bound only has to catch a stall. */
    private static final Duration ROUND_TRIP_BOUND = Duration.ofMillis(100);
    /** How long the bulk's progress must stand still for its stream to count as stalled. */
    private static final Duration STALLED_FOR = Duration.ofMillis(500);

    @Test
    void aStalledDownloadDoesNotHoldBackAnInteractiveStreamOnTheSameSession() throws Exception {
        AtomicLong bulkSent = new AtomicLong();
        LocalService service = closeAfterTest(LocalService.bulkOnRequest(BULK_BYTES, BULK_SEED, bulkSent));
        Tunnel tunnel = openTunnel(service);

        Socket bulk = visit(tunnel.port());
        bulk.getOutputStream().write(LocalService.BULK);
        long stalledAt = awaitStalled(bulkSent);
        assertThat(stalledAt).as("the bulk stopped partway").isPositive().isLessThan(BULK_BYTES);

        Socket interactive = visit(tunnel.port());
        long slowest = 0;
        for (int i = 0; i < ROUND_TRIPS; i++) {
            byte[] message = message(i);
            long started = System.nanoTime();
            assertThat(roundTrip(interactive, message)).isEqualTo(message);
            slowest = Math.max(slowest, System.nanoTime() - started);
        }
        System.out.printf(
                "Slowest of %d round trips beside a download stalled at %d bytes: %d µs%n",
                ROUND_TRIPS, stalledAt, TimeUnit.NANOSECONDS.toMicros(slowest));
        assertThat(Duration.ofNanos(slowest)).as("the slowest round trip").isLessThan(ROUND_TRIP_BOUND);
        assertThat(bulkSent.get()).as("the bulk is still held back").isEqualTo(stalledAt);

        bulk.setSoTimeout((int) Duration.ofMinutes(1).toMillis());
        assertThat(drain(bulk.getInputStream()))
                .isEqualTo(new Received(BULK_BYTES, expectedDigest(BULK_BYTES, BULK_SEED)));
        assertOpen(tunnel.handle());
    }

    /** Waits until the bulk has made progress and then made none for {@link #STALLED_FOR}; returns where it stopped. */
    private static long awaitStalled(AtomicLong sent) throws InterruptedException {
        await("the bulk has started", () -> sent.get() > 0);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        long last = sent.get();
        while (System.nanoTime() < deadline) {
            Thread.sleep(STALLED_FOR);
            long now = sent.get();
            if (now == last) {
                return now;
            }
            last = now;
        }
        throw new AssertionError("The bulk never stalled; it reached " + last + " bytes");
    }

    /** A message as long as the echo needs, numbered so a reply to the wrong one would show. */
    private static byte[] message(int number) {
        ByteBuffer message = ByteBuffer.allocate(MESSAGE);
        while (message.hasRemaining()) {
            message.putInt(number);
        }
        return message.array();
    }
}
