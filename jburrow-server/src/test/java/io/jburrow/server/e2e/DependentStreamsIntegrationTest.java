package io.jburrow.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Head-of-line blocking and deadlock: two streams whose local service
 * won't read A until B has delivered something ({@link LocalService#dependentPair}).
 *
 * <p>A sends more than every window between it and the service holds, so its bytes back up and its credit runs out.
 * Under phase 1's model the client would then stop reading the control connection, B's bytes would wait behind A's,
 * and the service would never read A: a deadlock. With per-stream credit B has its own, and A's echo arrives.
 */
@TestPropertySource(properties = "jburrow.tcp.port-range=31750-31799")
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
@ExtendWith(FlowStateDump.class)
class DependentStreamsIntegrationTest extends TunnelTestSupport {

    private static final long A_BYTES = 4L * 1024 * 1024;
    private static final long A_SEED = 41;
    private static final int B_BYTES = 1024;
    private static final Duration ECHO_DEADLINE = Duration.ofSeconds(30);

    @Test
    void aStreamWaitingOnAnotherCompletesOnceTheOtherDelivers() throws Exception {
        LocalService service = closeAfterTest(LocalService.dependentPair(B_BYTES));
        Tunnel tunnel = openTunnel(service);
        double blockedBefore = counter("jburrow.flow.blocked", "scope", "stream");

        Socket a = visit(tunnel.port());
        a.setSoTimeout((int) ECHO_DEADLINE.toMillis());
        await("the service has accepted A first", () -> service.accepted() == 1);
        CompletableFuture<String> sentByA = async(() -> {
            String digest = send(a.getOutputStream(), A_BYTES, A_SEED);
            a.shutdownOutput();
            return digest;
        });
        CompletableFuture<Received> echoedToA = async(() -> drain(a.getInputStream()));
        await(
                "A is held back for its own stream's credit",
                () -> counter("jburrow.flow.blocked", "scope", "stream") > blockedBefore);

        Socket b = visit(tunnel.port());
        send(b.getOutputStream(), B_BYTES, 42);
        b.shutdownOutput();

        assertThat(echoedToA.get(ECHO_DEADLINE.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new Received(A_BYTES, sentByA.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)));
        assertOpen(tunnel.handle());
    }
}
