package io.jburrow.server.core.tunnel.adapter.netty.http;

import static io.jburrow.server.core.tunnel.adapter.netty.http.HttpHarness.TIMEOUT;
import static io.jburrow.server.core.tunnel.adapter.netty.http.HttpHarness.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.jburrow.server.core.tunnel.adapter.netty.http.Visitor.Response;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Error pages: each page the exchange path renders, from a real
 * request, with its status, its code, and what happens to the connection after it. {@code RESPONSE_TIMEOUT} has its
 * own test.
 */
class ErrorPagesIntegrationTest {

    private static final String TRANSPORTS = "io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available";
    private static final long MAX_BODY = 1_000;

    private HttpHarness harness;
    private ScriptedPeer peer;
    private RegisteredTunnel tunnel;

    @AfterEach
    void stop() throws Exception {
        if (harness != null) {
            harness.close();
            assertThat(harness.streamQuota.inUse()).isZero();
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aNameNobodyHoldsIsNotFoundAndTheConnectionCarriesOn(EdgeTransport transport) throws Exception {
        start(transport, 10);
        Visitor visitor = harness.visitor("nobody-here.localhost");

        visitor.request("GET", "/");
        Response missing = visitor.response();
        visitor.host(tunnel.publicHost()).request("GET", "/");
        peer.send(Frame.Close.reset(open(), ErrorCode.LOCAL_SERVICE_ERROR));

        assertPage(missing, 404, "TUNNEL_NOT_FOUND");
        assertThat(missing.text()).contains("nobody-here.localhost");
        assertPage(visitor.response(), 502, "LOCAL_SERVICE_ERROR");
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void requestsTheEdgeWontForwardAreRefusedBeforeAnyStreamOpens(EdgeTransport transport) throws Exception {
        start(transport, 10);
        Visitor visitor = harness.visitor(tunnel.publicHost());

        visitor.request("GET", "/socket", "Upgrade", "websocket", "Connection", "Upgrade");
        assertPage(visitor.response(), 501, "UPGRADE_NOT_SUPPORTED");
        visitor.request("POST", "/", "Transfer-Encoding", "gzip", "Content-Length", "0");
        assertPage(visitor.response(), 400, "BAD_REQUEST");

        Visitor large = harness.visitor(tunnel.publicHost());
        large.request("POST", "/", "Content-Length", Long.toString(MAX_BODY + 1));
        assertPage(large.response(), 413, "REQUEST_TOO_LARGE");
        assertThat(large.closedByEdge()).isTrue();

        Visitor headers = harness.visitor(tunnel.publicHost());
        headers.request("GET", "/", "X-Big", "v".repeat(RequestHeads.MAX_VALUE_LENGTH + 1));
        assertPage(headers.response(), 431, "REQUEST_HEADERS_TOO_LARGE");

        assertThat(harness.metrics.events()).noneMatch(event -> event.startsWith("streamOpened"));
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aChunkedBodyThatPassesTheLimitResetsTheStreamAndIsTooLarge(EdgeTransport transport) throws Exception {
        start(transport, 10);
        Visitor visitor = harness.visitor(tunnel.publicHost());
        peer.autoGrant();

        visitor.request("POST", "/upload", "Transfer-Encoding", "chunked");
        int id = peer.expect(Frame.Open.class, TIMEOUT).streamId();
        visitor.chunk(new byte[(int) MAX_BODY]);
        visitor.chunk(new byte[1]);

        assertPage(visitor.response(), 413, "REQUEST_TOO_LARGE");
        assertThat(visitor.closedByEdge()).isTrue();
        assertThat(resetOf(id)).isEqualTo(ErrorCode.CANCELLED);
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aBodyThatDoesntDecodeResetsTheStreamAndIsABadRequest(EdgeTransport transport) throws Exception {
        start(transport, 10);
        Visitor visitor = harness.visitor(tunnel.publicHost());

        visitor.request("POST", "/upload", "Transfer-Encoding", "chunked");
        int id = peer.expect(Frame.Open.class, TIMEOUT).streamId();
        visitor.send("zz\r\nnot a chunk\r\n");

        assertPage(visitor.response(), 400, "BAD_REQUEST");
        assertThat(visitor.closedByEdge()).isTrue();
        assertThat(resetOf(id)).isEqualTo(ErrorCode.CANCELLED);
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void theLocalServiceFailingBeforeTheHeadIsABadGateway(EdgeTransport transport) throws Exception {
        start(transport, 10);
        Visitor visitor = harness.visitor(tunnel.publicHost());

        visitor.request("GET", "/");
        peer.send(Frame.Close.reset(open(), ErrorCode.LOCAL_SERVICE_UNREACHABLE));
        assertPage(visitor.response(), 502, "LOCAL_SERVICE_UNREACHABLE");
        visitor.request("GET", "/");
        peer.send(Frame.Close.reset(open(), ErrorCode.LOCAL_SERVICE_ERROR));
        assertPage(visitor.response(), 502, "LOCAL_SERVICE_ERROR");
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aRequestPastTheSessionsStreamLimitIsBusyAndSaysWhenToRetry(EdgeTransport transport) throws Exception {
        start(transport, 1);
        Visitor holding = harness.visitor(tunnel.publicHost());
        holding.request("GET", "/long-poll");
        open();

        Visitor busy = harness.visitor(tunnel.publicHost());
        busy.request("GET", "/");
        Response response = busy.response();

        assertPage(response, 503, "TUNNEL_BUSY");
        assertThat(response.header("retry-after")).isEqualTo("1");
        assertThat(harness.metrics.events()).contains("visitorRefused STREAM_LIMIT");
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aNewRequestWhileTheServerStopsIsToldToRetry(EdgeTransport transport) throws Exception {
        start(transport, 10);
        Visitor visitor = harness.visitor(tunnel.publicHost());
        visitor.request("GET", "/");
        peer.send(Frame.Close.reset(open(), ErrorCode.LOCAL_SERVICE_ERROR));
        visitor.response();

        harness.edge.beginShutdown();
        visitor.request("GET", "/");
        Response response = visitor.response();

        assertPage(response, 503, "SERVER_SHUTTING_DOWN");
        assertThat(response.header("retry-after")).isNotNull();
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void anExchangeOpenWhenItsSessionEndsGetsThePageForWhy(EdgeTransport transport) throws Exception {
        start(transport, 10);
        Visitor visitor = harness.visitor(tunnel.publicHost());
        visitor.request("GET", "/");
        open();

        peer.dropConnection();

        assertPage(visitor.response(), 404, "TUNNEL_NOT_FOUND");
        awaitTrue(() -> harness.registry.active() == 0);
    }

    private void start(EdgeTransport transport, int maxConcurrentStreams) {
        harness = new HttpHarness(HttpHarness.Settings.defaults(transport)
                .maxRequestBody(MAX_BODY)
                .streams(maxConcurrentStreams, 100));
        peer = harness.connect();
        peer.ignoreWindowUpdates();
        tunnel = harness.establish(peer, "pages");
    }

    private int open() {
        int id = peer.expect(Frame.Open.class, TIMEOUT).streamId();
        assertThat(peer.expect(Frame.Close.class, TIMEOUT).reset()).isEmpty();
        return id;
    }

    /** The code of the reset that ends {@code streamId}, skipping the request body before it. */
    private ErrorCode resetOf(int streamId) {
        while (true) {
            Frame frame = peer.expect(Frame.class, TIMEOUT);
            assertThat(frame.streamId()).isEqualTo(streamId);
            if (frame instanceof Frame.Close close) {
                return close.reset().orElseThrow();
            }
            frame.release();
        }
    }

    private static void assertPage(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.header("jburrow-error")).isEqualTo(code);
        assertThat(response.header("content-type")).isEqualTo("text/html; charset=utf-8");
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains(code);
    }
}
