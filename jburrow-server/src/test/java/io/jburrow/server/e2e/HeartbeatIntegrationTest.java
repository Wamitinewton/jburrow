package io.jburrow.server.e2e;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Liveness with short timings: the protocol's floor of a 1-second ping interval ({@code docs/protocol.md} §7.4), and
 * an idle timeout of twice that.
 */
@TestPropertySource(
        properties = {
            "jburrow.tcp.port-range=31200-31299",
            "jburrow.edge.ping-interval=1s",
            "jburrow.edge.idle-timeout=2s"
        })
class HeartbeatIntegrationTest extends TunnelTestSupport {

    private static final Duration PING_INTERVAL = Duration.ofSeconds(1);
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(2);
    /** Scheduling slack on a loaded build machine. */
    private static final Duration SLACK = Duration.ofSeconds(1);

    @Test
    void aSilentClientIsDroppedWithinTheIdleTimeoutAndItsPortFreed() throws Exception {
        double before = counter("jburrow.sessions.closed", "reason", "HEARTBEAT_TIMEOUT");
        ScriptedPeer client = scriptedClient();
        RegisteredTunnel tunnel = establish(client);
        assertThat(bindable(tunnel.publicPort()))
                .as("the tunnel holds its port")
                .isFalse();

        long silentFrom = System.nanoTime();
        client.silence();

        client.expect(Frame.Ping.class, IDLE_TIMEOUT.plus(SLACK));
        client.expectError(ErrorCode.HEARTBEAT_TIMEOUT, IDLE_TIMEOUT.plus(SLACK));
        assertThat(Duration.ofNanos(System.nanoTime() - silentFrom)).isLessThan(IDLE_TIMEOUT.plus(SLACK));

        await("the port is free again", () -> bindable(tunnel.publicPort()));
        await(
                "the session is counted as closed by HEARTBEAT_TIMEOUT",
                () -> counter("jburrow.sessions.closed", "reason", "HEARTBEAT_TIMEOUT") == before + 1);
    }

    /**
     * Nothing but liveness crosses the connection for several idle timeouts, and neither side times out.
     *
     * <p>Both sides run the same clock (protocol.md §10), so which one pings is a race. Answering a {@code PING} resets
     * the answerer's clock a round trip before the {@code PONG} resets the pinger's, so the pinging side tends to
     * alternate. The test counts liveness in both directions, never one side's {@code PING}s.
     */
    @Test
    void anIdleRealClientStaysConnected() throws Exception {
        LocalService echo = closeAfterTest(LocalService.echo());
        Tunnel tunnel = openTunnel(echo);
        double pingsBefore = framesBothWays("ping");
        double pongsBefore = framesBothWays("pong");

        Thread.sleep(IDLE_TIMEOUT.multipliedBy(2).plus(PING_INTERVAL));

        assertOpen(tunnel.handle());
        double pings = framesBothWays("ping") - pingsBefore;
        double pongs = framesBothWays("pong") - pongsBefore;
        assertThat(pings).as("PINGs either way").isGreaterThanOrEqualTo(3);
        // One exchange can straddle each end of the window.
        assertThat(pongs).as("PONGs either way, one per PING").isCloseTo(pings, within(1.0));
        byte[] message = "still here".getBytes(US_ASCII);
        assertThat(roundTrip(visit(tunnel.port()), message)).isEqualTo(message);
    }

    private double framesBothWays(String type) {
        return counter("jburrow.frames", "type", type, "direction", "sent")
                + counter("jburrow.frames", "type", type, "direction", "received");
    }
}
