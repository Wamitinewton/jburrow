package io.jburrow.server.core.tunnel.adapter.netty.edge;

import static io.jburrow.server.core.tunnel.adapter.netty.edge.RealEdge.awaitTrue;
import static io.jburrow.server.core.tunnel.adapter.netty.edge.RealEdge.read;
import static io.jburrow.server.core.tunnel.adapter.netty.edge.RealEdge.write;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.core.tunnel.adapter.netty.edge.RealEdge.Response;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * One edge connection carrying requests of different kinds, as one the ingress reuses can: {@code docs/phase-3-http-
 * tunnels/ARCHITECTURE.md} §Dispatch on {@code Host}, "Mixed connections still work".
 */
class MixedConnectionIntegrationTest {

    private RealEdge edge;

    @AfterEach
    void stop() throws Exception {
        if (edge != null) {
            edge.close();
        }
    }

    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void tunnelRequestsThenAConnectUpgradeOnOneConnectionReachTheSession(EdgeTransport transport) throws Exception {
        edge = new RealEdge(transport);

        try (Socket socket = edge.socket()) {
            InputStream in = socket.getInputStream();
            write(socket, edge.get("quiet-otter.localhost"));
            Response first = read(in);
            write(socket, edge.get("other-tunnel.localhost"));
            Response second = read(in);
            write(socket, edge.upgradeRequest());
            Response upgrade = read(in);

            assertThat(first.status()).isEqualTo(404);
            assertThat(first.header("jburrow-error")).isEqualTo("TUNNEL_NOT_FOUND");
            assertThat(second.status()).isEqualTo(404);
            assertThat(upgrade.status()).isEqualTo(101);
            assertThat(upgrade.header("sec-websocket-protocol")).isEqualTo("jburrow.v1-draft.2");
            awaitTrue(() -> edge.upgraded.size() == 1);
            assertThat(edge.upgraded.get(0).pipeline().get(HostDispatcher.EXCHANGE))
                    .isNull();
            assertThat(edge.admission.handshaking()).isOne();
        }
    }

    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void requestsForTwoTunnelsAlternateOnOneConnection(EdgeTransport transport) throws Exception {
        edge = new RealEdge(transport);

        try (Socket socket = edge.socket()) {
            InputStream in = socket.getInputStream();
            for (int i = 0; i < 6; i++) {
                String host = i % 2 == 0 ? "alpha-tunnel.localhost" : "beta-tunnel.localhost";
                write(socket, edge.get(host));
                Response response = read(in);

                assertThat(response.status()).isEqualTo(404);
                assertThat(response.body()).contains(host);
                assertThat(response.header("connection")).isNull();
            }
        }
        assertThat(edge.upgraded).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void pipelinedRequestsAreAnsweredInOrder(EdgeTransport transport) throws Exception {
        edge = new RealEdge(transport);

        try (Socket socket = edge.socket()) {
            InputStream in = socket.getInputStream();
            write(
                    socket,
                    edge.get("first-tunnel.localhost") + edge.get("nothing.test") + edge.get("third-tunnel.localhost"));

            assertThat(read(in).body()).contains("first-tunnel.localhost");
            assertThat(read(in).body()).contains("nothing.test");
            assertThat(read(in).body()).contains("third-tunnel.localhost");
        }
    }

    /**
     * A refused upgrade closes its connection ({@code docs/protocol.md} §3.5), so no tunnel request can follow it on
     * the same one; a connection that served tunnel requests first is refused exactly as a fresh one.
     */
    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void aRefusedConnectRequestAfterTunnelRequestsGetsPhaseOnesRefusalAndCloses(EdgeTransport transport)
            throws Exception {
        edge = new RealEdge(transport);

        try (Socket socket = edge.socket()) {
            InputStream in = socket.getInputStream();
            write(socket, edge.get("quiet-otter.localhost"));
            assertThat(read(in).status()).isEqualTo(404);
            write(socket, edge.upgradeRequest().replace("\r\n\r\n", "\r\nOrigin: https://evil.example\r\n\r\n"));
            Response refused = read(in);

            assertThat(refused.status()).isEqualTo(403);
            assertThat(refused.header("content-type")).isEqualTo("application/json");
            assertThat(ControlJson.create()
                            .readRefusal(refused.body().getBytes(StandardCharsets.UTF_8))
                            .code())
                    .isEqualTo(ErrorCode.FORBIDDEN_ORIGIN);
            assertThat(in.read()).isEqualTo(-1);
        }
        assertThat(edge.upgraded).isEmpty();
    }
}
