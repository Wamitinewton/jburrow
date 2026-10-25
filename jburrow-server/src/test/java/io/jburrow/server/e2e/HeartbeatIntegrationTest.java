package io.jburrow.server.e2e;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

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
            "jburrow.tcp.port-range=41200-41299",
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
     * Nothing but liveness crosses the connection for several idle timeouts. The server's clock fires first, so the
     * server pings and the client answers; neither side times out.
     */
    @Test
    void anIdleRealClientStaysConnected() throws Exception {
        LocalService echo = closeAfterTest(LocalService.echo());
        Tunnel tunnel = openTunnel(echo);
        double pingsBefore = counter("jburrow.frames", "type", "ping", "direction", "sent");
        double pongsBefore = counter("jburrow.frames", "type", "pong", "direction", "received");

        Thread.sleep(IDLE_TIMEOUT.multipliedBy(2).plus(PING_INTERVAL));

        assertOpen(tunnel.handle());
        assertThat(counter("jburrow.frames", "type", "ping", "direction", "sent") - pingsBefore)
                .as("PINGs sent to the client")
                .isGreaterThanOrEqualTo(3);
        assertThat(counter("jburrow.frames", "type", "pong", "direction", "received") - pongsBefore)
                .as("PONGs from the client")
                .isGreaterThanOrEqualTo(3);
        byte[] message = "still here".getBytes(US_ASCII);
        assertThat(roundTrip(visit(tunnel.port()), message)).isEqualTo(message);
    }
}
