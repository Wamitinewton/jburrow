package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import static io.jburrow.server.core.tunnel.adapter.netty.tcp.TunnelHarness.TIMEOUT;
import static io.jburrow.server.core.tunnel.adapter.netty.tcp.TunnelHarness.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Admitting streams over real sockets: a session's stream limit, the
 * server-wide quota, and the quota coming back to zero however its streams end.
 */
class StreamAdmissionIntegrationTest {

    private static final String TRANSPORTS = "io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available";
    private static final int FIRST_PORT = 30_500;

    private final List<Socket> visitors = new ArrayList<>();
    private TunnelHarness harness;

    @AfterEach
    void stop() throws Exception {
        closeVisitors();
        if (harness != null) {
            harness.close();
            assertThat(harness.streamQuota.inUse()).isZero();
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aSessionTakesAThousandVisitorsAndClosesTheNextOneUnannounced(EdgeTransport transport) throws Exception {
        start(transport, 1_000, 2_000);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null);
        for (int i = 1; i <= 1_000; i++) {
            visitors.add(visitor(tunnel));
            assertThat(peer.expect(Frame.Open.class, TIMEOUT).streamId()).isEqualTo(i);
        }

        try (Socket refused = visitor(tunnel)) {
            assertThat(refused.getInputStream().read()).isEqualTo(-1);
        }

        awaitTrue(() -> harness.metrics.events().contains("visitorRefused STREAM_LIMIT"));
        assertThat(harness.streamQuota.inUse()).isEqualTo(1_000);
        closeVisitors();
        awaitTrue(() -> harness.streamQuota.inUse() == 0);
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void theServerWideQuotaRefusesAVisitorOnAnySessionOnceItIsFull(EdgeTransport transport) throws Exception {
        start(transport, 10, 10);
        ScriptedPeer first = harness.connect();
        RegisteredTunnel firstTunnel = harness.establish(first, null);
        ScriptedPeer second = harness.connect();
        RegisteredTunnel secondTunnel = harness.establish(second, null);
        for (int i = 0; i < 6; i++) {
            visitors.add(visitor(firstTunnel));
            first.expect(Frame.Open.class, TIMEOUT);
        }
        for (int i = 0; i < 4; i++) {
            visitors.add(visitor(secondTunnel));
            second.expect(Frame.Open.class, TIMEOUT);
        }

        try (Socket refused = visitor(secondTunnel)) {
            assertThat(refused.getInputStream().read()).isEqualTo(-1);
        }

        awaitTrue(() -> harness.metrics.events().contains("visitorRefused SERVER_STREAM_LIMIT"));
        assertThat(harness.streamQuota.inUse()).isEqualTo(10);
        Socket oldest = visitors.removeFirst();
        oldest.setSoLinger(true, 0);
        oldest.close();
        awaitTrue(() -> harness.streamQuota.inUse() == 9);
        visitors.add(visitor(secondTunnel));
        assertThat(second.expect(Frame.Open.class, TIMEOUT).streamId()).isEqualTo(5);
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void streamsEndedByTheClientByAResetAndByTheSessionClosingAllGiveTheirQuotaBack(EdgeTransport transport)
            throws Exception {
        start(transport, 1_000, 2_000);
        ScriptedPeer peer = harness.connect();
        RegisteredTunnel tunnel = harness.establish(peer, null);
        for (int i = 0; i < 3; i++) {
            visitors.add(visitor(tunnel));
            peer.expect(Frame.Open.class, TIMEOUT);
        }

        peer.send(Frame.Close.reset(1, ErrorCode.CANCELLED));
        visitors.get(1).setSoLinger(true, 0);
        visitors.get(1).close();
        awaitTrue(() -> harness.streamQuota.inUse() == 1);
        peer.close();

        awaitTrue(() -> harness.streamQuota.inUse() == 0);
    }

    private void start(EdgeTransport transport, int maxConcurrentStreams, int maxStreams) {
        harness = new TunnelHarness(
                PortRange.parse(FIRST_PORT + "-" + (FIRST_PORT + 9)),
                new Random(),
                TunnelHarness.Settings.defaults().transport(transport).streams(maxConcurrentStreams, maxStreams));
    }

    /** Resets every visitor: a visitor's {@code FIN} only half-closes its stream, which keeps its quota. */
    private void closeVisitors() throws IOException {
        for (Socket visitor : visitors) {
            if (!visitor.isClosed()) {
                visitor.setSoLinger(true, 0);
                visitor.close();
            }
        }
        visitors.clear();
    }

    private static Socket visitor(RegisteredTunnel tunnel) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), tunnel.publicPort());
        socket.setSoTimeout((int) TIMEOUT.toMillis());
        return socket;
    }
}
