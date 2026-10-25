package io.jburrow.server.e2e;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.client.TunnelStartException;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.e2e.TunnelTestSupport.Tunnel;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;

/**
 * One run through everything the server observes, for the metrics and log tests: a refused upgrade, an established
 * session with a stream of known size, a visitor refused while that stream is open, a stream the local service resets,
 * a session with a bad token, a client that goes silent until the heartbeat ends it, and an orderly close.
 *
 * <p>The tunnel's local service echoes, except that a stream starting with {@link #RESET} is reset.
 *
 * @param sessionId the established session's id
 * @param tunnelPort its tunnel's public port
 * @param echoedBytes the size of the first stream, each way
 * @param resetStreamBytes what the visitor sent on the stream that was reset
 */
record ObservedScenario(String sessionId, int tunnelPort, int echoedBytes, int resetStreamBytes) {

    /** Sent as tunnel payload; it must never reach a log. */
    static final String MARKER = "PAYLOAD-MARKER-7f3a9c-never-logged";

    static final String CLIENT_NAME = "jburrow-observed";
    static final String CLIENT_VERSION = "9.8.7";

    private static final byte[] RESET = "RESET".getBytes(US_ASCII);

    /** The streams the scenario opens: the echoed one and the reset one. */
    static final int STREAMS = 2;

    static ObservedScenario run(TunnelTestSupport test) throws Exception {
        refuseAnUpgrade(test);

        LocalService service = test.closeAfterTest(LocalService.start(ObservedScenario::echoUnlessReset));
        Tunnel tunnel =
                test.openTunnel(test.client(TunnelTestSupport.ALICE_TOKEN, TunnelTestSupport.tcp(service.port()))
                        .clientInfo(CLIENT_NAME, CLIENT_VERSION));

        byte[] payload = (MARKER + " ").repeat(100).getBytes(US_ASCII);
        try (Socket visitor = test.visit(tunnel.port())) {
            assertThat(TunnelTestSupport.roundTrip(visitor, payload)).isEqualTo(payload);
            try (Socket refused = test.visit(tunnel.port())) {
                assertThat(refused.getInputStream().read()).isEqualTo(-1);
            }
        }
        tunnel.events().awaitStreamClosed(TunnelTestSupport.TIMEOUT);
        test.awaitNoStreams();

        try (Socket visitor = test.visit(tunnel.port())) {
            visitor.getOutputStream().write(RESET);
            assertThatThrownBy(() -> visitor.getInputStream().read()).isInstanceOf(SocketException.class);
        }
        assertThat(tunnel.events().awaitStreamClosed(TunnelTestSupport.TIMEOUT).reset())
                .contains(ErrorCode.PEER_RESET);

        assertThatThrownBy(
                        () -> test.openTunnel(TunnelTestSupport.UNKNOWN_TOKEN, TunnelTestSupport.tcp(service.port())))
                .isInstanceOfSatisfying(
                        TunnelStartException.class,
                        failure -> assertThat(failure.code()).contains(ErrorCode.AUTHTOKEN_INVALID));

        ScriptedPeer silent = test.scriptedClient();
        TunnelTestSupport.establish(silent);
        silent.silence();
        silent.expect(Frame.Ping.class, TunnelTestSupport.TIMEOUT);
        silent.expectError(ErrorCode.HEARTBEAT_TIMEOUT, TunnelTestSupport.TIMEOUT);

        String sessionId = tunnel.handle().sessionId();
        int tunnelPort = tunnel.port();
        tunnel.handle().close();
        tunnel.awaitClosed(TunnelTestSupport.TIMEOUT);
        test.await("every session has ended", () -> test.gauge("jburrow.sessions.active") == 0);
        test.awaitNoReservedPorts();
        return new ObservedScenario(sessionId, tunnelPort, payload.length, RESET.length);
    }

    /** A plain GET with no subprotocol, which the edge refuses with {@code UNSUPPORTED_PROTOCOL_VERSION}. */
    private static void refuseAnUpgrade(TunnelTestSupport test) throws IOException, InterruptedException {
        HttpResponse<String> refused = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + test.edge.boundPort() + "/v1/connect"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION.code());
    }

    private static void echoUnlessReset(Socket connection) throws IOException {
        InputStream in = connection.getInputStream();
        byte[] first = in.readNBytes(RESET.length);
        if (Arrays.equals(first, RESET)) {
            connection.setSoLinger(true, 0);
            return;
        }
        connection.getOutputStream().write(first);
        LocalService.copy(in, connection.getOutputStream());
        connection.shutdownOutput();
    }
}
