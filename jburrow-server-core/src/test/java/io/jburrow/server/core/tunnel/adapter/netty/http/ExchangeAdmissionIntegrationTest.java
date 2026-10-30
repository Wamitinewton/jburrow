package io.jburrow.server.core.tunnel.adapter.netty.http;

import static io.jburrow.server.core.tunnel.adapter.netty.http.HttpHarness.TIMEOUT;
import static io.jburrow.server.core.tunnel.adapter.netty.http.HttpHarness.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Admitting streams, for exchanges: an exchange is a stream under
 * its session's limit and the server-wide quota like a TCP visitor, and the quota comes back to zero however exchanges
 * end.
 */
class ExchangeAdmissionIntegrationTest {

    private static final String TRANSPORTS = "io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available";

    private final List<Visitor> visitors = new ArrayList<>();
    private HttpHarness harness;

    @AfterEach
    void stop() throws Exception {
        if (harness != null) {
            for (Visitor visitor : visitors) {
                visitor.close();
            }
            awaitTrue(() -> harness.streamQuota.inUse() == 0);
            harness.close();
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void exchangesCountAgainstTheSessionsLimit(EdgeTransport transport) throws Exception {
        harness = new HttpHarness(HttpHarness.Settings.defaults(transport).streams(3, 100));
        ScriptedPeer peer = peer();
        RegisteredTunnel tunnel = harness.establish(peer, "limited");
        for (int i = 0; i < 3; i++) {
            pending(tunnel, peer);
        }

        Visitor refused = visitor(tunnel);
        refused.request("GET", "/");

        assertThat(refused.response().status()).isEqualTo(503);
        assertThat(harness.streamQuota.inUse()).isEqualTo(3);
        assertThat(harness.metrics.events()).contains("visitorRefused STREAM_LIMIT");
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void exchangesOfEverySessionShareTheServerWideQuota(EdgeTransport transport) throws Exception {
        harness = new HttpHarness(HttpHarness.Settings.defaults(transport).streams(3, 4));
        ScriptedPeer first = peer();
        RegisteredTunnel firstTunnel = harness.establish(first, "first");
        ScriptedPeer second = peer();
        RegisteredTunnel secondTunnel = harness.establish(second, "second");
        for (int i = 0; i < 3; i++) {
            pending(firstTunnel, first);
        }
        pending(secondTunnel, second);

        Visitor refused = visitor(secondTunnel);
        refused.request("GET", "/");

        assertThat(refused.response().header("jburrow-error")).isEqualTo("TUNNEL_BUSY");
        assertThat(harness.metrics.events()).contains("visitorRefused SERVER_STREAM_LIMIT");
        assertThat(harness.streamQuota.inUse()).isEqualTo(4);
    }

    private ScriptedPeer peer() {
        ScriptedPeer peer = harness.connect();
        peer.ignoreWindowUpdates();
        return peer;
    }

    /** An exchange whose stream is open and waits for its response. */
    private void pending(RegisteredTunnel tunnel, ScriptedPeer peer) throws Exception {
        visitor(tunnel).request("GET", "/wait");
        peer.expect(Frame.Open.class, TIMEOUT);
        peer.expect(Frame.Close.class, TIMEOUT);
    }

    private Visitor visitor(RegisteredTunnel tunnel) throws Exception {
        Visitor visitor = harness.visitor(tunnel.publicHost());
        visitors.add(visitor);
        return visitor;
    }
}
