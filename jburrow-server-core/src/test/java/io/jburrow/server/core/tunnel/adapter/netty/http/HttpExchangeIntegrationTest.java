package io.jburrow.server.core.tunnel.adapter.netty.http;

import static io.jburrow.server.core.tunnel.adapter.netty.http.HttpHarness.TIMEOUT;
import static io.jburrow.server.core.tunnel.adapter.netty.http.HttpHarness.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.server.core.tunnel.adapter.netty.control.SessionContext;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.jburrow.server.core.tunnel.adapter.netty.http.Visitor.Response;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §An exchange, over real sockets: a real edge, a scripted client, and
 * a visitor whose connection is on another event loop than the session's, so every hand-over between the exchange's
 * two halves crosses threads.
 */
class HttpExchangeIntegrationTest {

    private static final String TRANSPORTS = "io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available";

    private HttpHarness harness;
    private ScriptedPeer peer;
    private RegisteredTunnel tunnel;
    private SessionContext session;

    @AfterEach
    void stop() throws Exception {
        if (harness != null) {
            harness.close();
            assertThat(harness.streamQuota.inUse()).isZero();
        }
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aGetBecomesAStreamWithTheEdgesHeadAndItsResponseComesBack(EdgeTransport transport) throws Exception {
        start(transport);
        Visitor visitor = harness.visitorOnAnotherLoop(tunnel.publicHost(), session);

        visitor.request("GET", "/hello?name=otter", "Accept", "text/plain", "X-Forwarded-For", "198.51.100.7");

        Frame.Open open = peer.expect(Frame.Open.class, TIMEOUT);
        HttpRequestHead request = open.message().request();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.target()).isEqualTo("/hello?name=otter");
        assertThat(request.body()).isEqualTo(BodyFraming.EMPTY);
        assertThat(request.headers().single("host")).contains(tunnel.publicHost() + ":" + harness.edge.boundPort());
        assertThat(request.headers().single("accept")).contains("text/plain");
        assertThat(request.headers().all("x-forwarded-for")).containsExactly("198.51.100.7");
        assertThat(request.headers().all("via")).containsExactly("1.1 jburrow");
        assertThat(open.message().tunnelId()).isEqualTo(tunnel.tunnelId());
        assertThat(peer.expect(Frame.Close.class, TIMEOUT).reset()).isEmpty();

        respond(open.streamId(), 200, "hello, otter");

        Response response = visitor.response();
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.text()).isEqualTo("hello, otter");
        assertThat(response.header("content-type")).isEqualTo("text/plain");
        assertThat(response.header("connection")).isNull();
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aPostWithALengthIsForwardedAsDataThenClose(EdgeTransport transport) throws Exception {
        start(transport);
        Visitor visitor = harness.visitorOnAnotherLoop(tunnel.publicHost(), session);
        byte[] body = pattern(50_000);

        visitor.request("POST", "/items", body, "Content-Type", "application/octet-stream");

        Frame.Open open = peer.expect(Frame.Open.class, TIMEOUT);
        assertThat(open.message().request().body()).isEqualTo(BodyFraming.LENGTH);
        assertThat(open.message().request().headers().contentLength()).hasValue(body.length);
        assertThat(receiveBody(open.streamId())).isEqualTo(body);

        respond(open.streamId(), 201, "created");
        assertThat(visitor.response().status()).isEqualTo(201);
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aChunkedPostArrivesWithoutItsChunkingAndAChunkedResponseLeavesWithIt(EdgeTransport transport)
            throws Exception {
        start(transport);
        Visitor visitor = harness.visitorOnAnotherLoop(tunnel.publicHost(), session);

        visitor.request("POST", "/stream", "Transfer-Encoding", "chunked");
        visitor.chunk("first ".getBytes(StandardCharsets.US_ASCII));
        visitor.chunk("second ".getBytes(StandardCharsets.US_ASCII));
        visitor.chunk("third".getBytes(StandardCharsets.US_ASCII));
        visitor.chunk(new byte[0]);

        Frame.Open open = peer.expect(Frame.Open.class, TIMEOUT);
        assertThat(open.message().request().body()).isEqualTo(BodyFraming.CHUNKED);
        assertThat(open.message().request().headers().all("transfer-encoding")).isEmpty();
        assertThat(new String(receiveBody(open.streamId()), StandardCharsets.US_ASCII))
                .isEqualTo("first second third");

        int id = open.streamId();
        peer.send(new Frame.Headers(id, new HttpResponseHead(200, HeaderFields.of(), BodyFraming.CHUNKED)));
        peer.sendData(id, ascii("one "), TIMEOUT);
        peer.sendData(id, ascii("two"), TIMEOUT);
        peer.send(Frame.Close.half(id));

        Response response = visitor.response();
        assertThat(response.header("transfer-encoding")).isEqualTo("chunked");
        assertThat(response.text()).isEqualTo("one two");
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void responsesWithoutABodyKeepTheConnectionForTheNextRequest(EdgeTransport transport) throws Exception {
        start(transport);
        Visitor visitor = harness.visitorOnAnotherLoop(tunnel.publicHost(), session);

        visitor.request("HEAD", "/page");
        int head = expectOpen();
        peer.send(new Frame.Headers(
                head, new HttpResponseHead(200, HeaderFields.of("content-length", "1043"), BodyFraming.EMPTY)));
        peer.send(Frame.Close.half(head));
        Response toHead = visitor.response(true);

        visitor.request("GET", "/gone");
        int noContent = expectOpen();
        peer.send(new Frame.Headers(noContent, new HttpResponseHead(204, HeaderFields.of(), BodyFraming.EMPTY)));
        peer.send(Frame.Close.half(noContent));
        Response empty = visitor.response();

        visitor.request("GET", "/cached", "If-None-Match", "\"v1\"");
        int notModified = expectOpen();
        peer.send(new Frame.Headers(
                notModified,
                new HttpResponseHead(
                        304, HeaderFields.of("etag", "\"v1\"", "content-length", "1043"), BodyFraming.EMPTY)));
        peer.send(Frame.Close.half(notModified));
        Response cached = visitor.response();

        visitor.request("GET", "/after");
        respond(expectOpen(), 200, "still here");

        assertThat(toHead.status()).isEqualTo(200);
        assertThat(toHead.header("content-length")).isEqualTo("1043");
        assertThat(empty.status()).isEqualTo(204);
        assertThat(empty.header("content-length")).isNull();
        assertThat(cached.status()).isEqualTo(304);
        assertThat(cached.header("content-length")).isEqualTo("1043");
        assertThat(visitor.response().text()).isEqualTo("still here");
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void earlyHintsReachTheVisitorBeforeTheFinalResponse(EdgeTransport transport) throws Exception {
        start(transport);
        Visitor visitor = harness.visitorOnAnotherLoop(tunnel.publicHost(), session);

        visitor.request("GET", "/");
        int id = expectOpen();
        peer.send(new Frame.Headers(
                id, new HttpResponseHead(103, HeaderFields.of("link", "</app.css>; rel=preload"), null)));
        Response hints = visitor.response();
        respond(id, 200, "page");

        assertThat(hints.status()).isEqualTo(103);
        assertThat(hints.header("link")).isEqualTo("</app.css>; rel=preload");
        assertThat(visitor.response().text()).isEqualTo("page");
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aResponseBeforeTheRequestEndsIsDeliveredAndTheConnectionThenCloses(EdgeTransport transport) throws Exception {
        start(transport);
        Visitor visitor = harness.visitorOnAnotherLoop(tunnel.publicHost(), session);

        visitor.request("PUT", "/upload", "Content-Length", "100000");
        visitor.send(pattern(10_000));
        int id = expectOpen();
        peer.send(new Frame.Headers(
                id, new HttpResponseHead(413, HeaderFields.of("content-length", "4"), BodyFraming.LENGTH)));
        peer.sendData(id, ascii("nope"), TIMEOUT);
        peer.send(Frame.Close.half(id));
        peer.send(Frame.Close.reset(id, ErrorCode.CANCELLED));

        Response response = visitor.response();
        assertThat(response.status()).isEqualTo(413);
        assertThat(response.text()).isEqualTo("nope");
        assertThat(visitor.closedByEdge()).isTrue();
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aResetBeforeTheHeadIsAPageAndTheConnectionCarriesTheNextRequest(EdgeTransport transport) throws Exception {
        start(transport);
        Visitor visitor = harness.visitorOnAnotherLoop(tunnel.publicHost(), session);

        visitor.request("GET", "/");
        peer.send(Frame.Close.reset(expectOpen(), ErrorCode.LOCAL_SERVICE_UNREACHABLE));
        Response unreachable = visitor.response();
        visitor.request("GET", "/");
        peer.send(Frame.Close.reset(expectOpen(), ErrorCode.CANCELLED));
        Response cancelled = visitor.response();

        assertThat(unreachable.status()).isEqualTo(502);
        assertThat(unreachable.header("jburrow-error")).isEqualTo("LOCAL_SERVICE_UNREACHABLE");
        assertThat(cancelled.status()).isEqualTo(502);
        assertThat(cancelled.header("jburrow-error")).isEqualTo("LOCAL_SERVICE_ERROR");
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void aResetAfterTheHeadCutsTheResponseShort(EdgeTransport transport) throws Exception {
        start(transport);
        Visitor visitor = harness.visitorOnAnotherLoop(tunnel.publicHost(), session);

        visitor.request("GET", "/");
        int id = expectOpen();
        peer.send(new Frame.Headers(
                id, new HttpResponseHead(200, HeaderFields.of("content-length", "100"), BodyFraming.LENGTH)));
        peer.sendData(id, ascii("partial"), TIMEOUT);
        peer.send(Frame.Close.reset(id, ErrorCode.LOCAL_SERVICE_ERROR));

        Response cut = visitor.response();
        assertThat(cut.status()).isEqualTo(200);
        assertThat(cut.text()).isEqualTo("partial");
        assertThat(visitor.closedByEdge()).isTrue();
    }

    @ParameterizedTest
    @MethodSource(TRANSPORTS)
    void oneConnectionCarriesRequestsForTwoTunnelsInTurn(EdgeTransport transport) throws Exception {
        start(transport);
        ScriptedPeer other = harness.connect();
        other.ignoreWindowUpdates();
        RegisteredTunnel beta = harness.establish(other, "beta-tunnel");
        Visitor visitor = harness.visitor(tunnel.publicHost());

        for (int i = 0; i < 6; i++) {
            boolean alpha = i % 2 == 0;
            ScriptedPeer serving = alpha ? peer : other;
            visitor.host(alpha ? tunnel.publicHost() : beta.publicHost()).request("GET", "/" + i);
            Frame.Open open = serving.expect(Frame.Open.class, TIMEOUT);
            serving.expect(Frame.Close.class, TIMEOUT);
            assertThat(open.message().request().target()).isEqualTo("/" + i);
            respond(serving, open.streamId(), 200, alpha ? "alpha" : "beta");

            assertThat(visitor.response().text()).isEqualTo(alpha ? "alpha" : "beta");
        }
        awaitTrue(() -> harness.streamQuota.inUse() == 0);
    }

    private void start(EdgeTransport transport) {
        harness = new HttpHarness(HttpHarness.Settings.defaults(transport));
        peer = harness.connect();
        peer.ignoreWindowUpdates();
        peer.autoGrant();
        tunnel = harness.establish(peer, "quiet-otter");
        session = harness.onlySession();
    }

    /** Waits for the next exchange's {@code OPEN} and the {@code CLOSE} of its empty body. */
    private int expectOpen() {
        Frame.Open open = peer.expect(Frame.Open.class, TIMEOUT);
        if (open.message().request().body() == BodyFraming.EMPTY) {
            assertThat(peer.expect(Frame.Close.class, TIMEOUT).reset()).isEmpty();
        }
        return open.streamId();
    }

    /** The request body on {@code streamId}, up to the server's {@code CLOSE}. */
    private byte[] receiveBody(int streamId) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            Frame frame = peer.expect(Frame.class, TIMEOUT);
            assertThat(frame.streamId()).isEqualTo(streamId);
            if (frame instanceof Frame.Data data) {
                byte[] bytes = new byte[data.payload().readableBytes()];
                data.payload().readBytes(bytes);
                data.release();
                body.writeBytes(bytes);
            } else {
                assertThat(frame)
                        .isInstanceOfSatisfying(
                                Frame.Close.class,
                                close -> assertThat(close.reset()).isEmpty());
                return body.toByteArray();
            }
        }
    }

    private void respond(int streamId, int status, String body) {
        respond(peer, streamId, status, body);
    }

    private static void respond(ScriptedPeer peer, int streamId, int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        peer.send(new Frame.Headers(
                streamId,
                new HttpResponseHead(
                        status,
                        HeaderFields.of("content-type", "text/plain", "content-length", Integer.toString(bytes.length)),
                        BodyFraming.LENGTH)));
        peer.sendData(streamId, Unpooled.wrappedBuffer(bytes), TIMEOUT);
        peer.send(Frame.Close.half(streamId));
    }

    private static ByteBuf ascii(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
    }

    private static byte[] pattern(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }
}
