package io.jburrow.client.tunnel.adapter.netty;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.ClientSettings;
import io.jburrow.client.tunnel.application.port.TunnelEvents;
import io.jburrow.client.tunnel.domain.ClientSession;
import io.jburrow.client.tunnel.domain.ExchangeSummary;
import io.jburrow.client.tunnel.domain.HostHeader;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.ClientInfo;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.Registered;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.ServerHello;
import io.jburrow.protocol.control.ServerInfo;
import io.jburrow.protocol.control.SessionLimits;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.testsupport.DuplexEmbeddedChannel;
import io.jburrow.protocol.wire.ErrorCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * One HTTP exchange through the connector and its {@link LocalExchangeHandler}, with no sockets: the request each head
 * becomes on the local connection, and each response the local service can give, valid or not, as the frames the
 * server receives. The local connection's bytes are read as ISO 8859-1, one byte to one character.
 */
class LocalExchangeHandlerTest {

    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 3_000);
    private static final LocalTarget TARGET = LocalTarget.of("127.0.0.1", 3_000);
    private static final WriteBufferWaterMark ROOMY = new WriteBufferWaterMark(1024 * 1024, 2 * 1024 * 1024);
    private static final HttpRequestHead GET =
            request("GET", "/items?page=2", BodyFraming.EMPTY, "host", "x.example.dev", "accept", "*/*");

    private final EmbeddedChannel control = new EmbeddedChannel();
    private final List<ExchangeSummary> exchanges = new ArrayList<>();
    private final List<String> closedStreams = new ArrayList<>();
    private DuplexEmbeddedChannel local;
    private LocalConnector connector;
    /** What the next {@code OPEN}'s dial returns. */
    private Future<Channel> pendingDial;
    /** A dial still connecting, for {@link #openConnecting}. */
    private Promise<Channel> connecting;

    @AfterEach
    void tearDown() {
        if (local != null) {
            local.finishAndReleaseAll();
        }
        control.finishAndReleaseAll();
    }

    // The request.

    @Test
    void aRequestKeepsItsFieldsInOrderAndItsTargetAsSent() {
        open(request(
                "GET",
                "/a%2Fb?q=1&q=2",
                BodyFraming.EMPTY,
                "accept",
                "*/*",
                "host",
                "x.example.dev",
                "via",
                "1.1 jburrow"));

        assertThat(written())
                .isEqualTo(
                        "GET /a%2Fb?q=1&q=2 HTTP/1.1\r\naccept: */*\r\nhost: x.example.dev\r\nvia: 1.1 jburrow\r\n\r\n");
    }

    @Test
    void aBodyOfKnownLengthIsWrittenAsItArrives() {
        open(request("POST", "/", BodyFraming.LENGTH, "host", "x", "content-length", "5"));
        written();

        serverData("hel");
        serverData("lo");
        serverClose();

        assertThat(written()).isEqualTo("hello");
    }

    @Test
    void aChunkedBodyIsFramedAgainAndEndedWithAnEmptyTrailer() {
        open(request("POST", "/", BodyFraming.CHUNKED, "host", "x"));

        assertThat(written()).isEqualTo("POST / HTTP/1.1\r\nhost: x\r\ntransfer-encoding: chunked\r\n\r\n");
        serverData("hello");
        serverClose();
        assertThat(written()).isEqualTo("5\r\nhello\r\n0\r\n\r\n");
    }

    @ParameterizedTest(name = "content-length: 0 with body {0}")
    @ValueSource(strings = {"EMPTY", "LENGTH"})
    void aZeroContentLengthIsKeptEitherWay(String framing) {
        open(request("POST", "/", BodyFraming.valueOf(framing), "host", "x", "content-length", "0"));
        serverClose();

        assertThat(written()).isEqualTo("POST / HTTP/1.1\r\nhost: x\r\ncontent-length: 0\r\n\r\n");
    }

    @Test
    void dataThatArrivesWhileConnectingIsWrittenAfterTheHead() {
        openConnecting(request("PUT", "/", BodyFraming.LENGTH, "host", "x", "content-length", "2"));
        serverData("ok");
        serverClose();

        connectNow();

        assertThat(written()).isEqualTo("PUT / HTTP/1.1\r\nhost: x\r\ncontent-length: 2\r\n\r\nok");
    }

    @Test
    void rewritingTheHostReplacesItWhereItStands() {
        open(GET, HostHeader.REWRITE);

        assertThat(written()).isEqualTo("GET /items?page=2 HTTP/1.1\r\nhost: 127.0.0.1:3000\r\naccept: */*\r\n\r\n");
    }

    @Test
    void expectIsNeverForwardedAndNoConnectionFieldIsAdded() {
        open(request("POST", "/", BodyFraming.LENGTH, "host", "x", "expect", "100-continue", "content-length", "1"));

        String head = written();
        assertThat(head).isEqualTo("POST / HTTP/1.1\r\nhost: x\r\ncontent-length: 1\r\n\r\n");
        assertThat(head.toLowerCase()).doesNotContain("connection", "upgrade", "keep-alive");
    }

    @Test
    void aValueAboveAsciiReachesTheLocalServiceAsTheSameByte() {
        open(request("GET", "/", BodyFraming.EMPTY, "host", "x", "x-name", "café"));

        assertThat(written()).contains("x-name: café\r\n");
    }

    // The response.

    @Test
    void aResponseOfKnownLengthBecomesHeadersDataAndClose() {
        open(GET);
        serverClose();

        respond("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello");

        assertThat(sent())
                .containsExactly(
                        headers(200, BodyFraming.LENGTH, "content-type", "text/plain", "content-length", "5"),
                        "DATA hello",
                        "CLOSE");
        assertThat(local.isOpen()).isFalse();
    }

    @Test
    void connectionSpecificFieldsAndTheNamesConnectionListsAreRemoved() {
        open(GET);
        serverClose();

        respond("HTTP/1.1 200 OK\r\nConnection: keep-alive, X-Hop\r\nKeep-Alive: timeout=5\r\nX-Hop: 1\r\n"
                + "Set-Cookie: a=1\r\nSet-Cookie: b=2\r\nContent-Length: 0\r\n\r\n");

        assertThat(sent())
                .containsExactly(
                        headers(
                                200,
                                BodyFraming.LENGTH,
                                "set-cookie",
                                "a=1",
                                "set-cookie",
                                "b=2",
                                "content-length",
                                "0"),
                        "CLOSE");
    }

    @Test
    void aChunkedResponseLosesItsChunkFraming() {
        open(GET);
        serverClose();

        respond(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\nX-Trailer: t\r\n\r\n");

        assertThat(sent()).containsExactly(headers(200, BodyFraming.CHUNKED), "DATA abc", "DATA de", "CLOSE");
    }

    @Test
    void aCloseDelimitedResponseIsChunkedAndEndsWithTheConnection() {
        open(GET);
        serverClose();

        respond("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nabc");
        local.receiveFin();

        assertThat(sent())
                .containsExactly(headers(200, BodyFraming.CHUNKED, "content-type", "text/plain"), "DATA abc", "CLOSE");
        assertThat(exchanges.getFirst().completed()).isTrue();
    }

    @Test
    void aResponseToHeadIsEmptyAndKeepsItsContentLength() {
        open(request("HEAD", "/", BodyFraming.EMPTY, "host", "x"));
        serverClose();

        respond("HTTP/1.1 200 OK\r\nContent-Length: 1043\r\n\r\n");

        assertThat(sent()).containsExactly(headers(200, BodyFraming.EMPTY, "content-length", "1043"), "CLOSE");
    }

    @ParameterizedTest(name = "{0} is empty")
    @ValueSource(ints = {204, 304})
    void aNoContentOrNotModifiedResponseIsEmpty(int status) {
        open(GET);
        serverClose();

        respond("HTTP/1.1 " + status + " X\r\nETag: \"1\"\r\n\r\n");

        assertThat(sent()).containsExactly(headers(status, BodyFraming.EMPTY, "etag", "\"1\""), "CLOSE");
    }

    @Test
    void namesAreLowerCasedAndValuesKeepTheirBytes() {
        open(GET);
        serverClose();

        respond("HTTP/1.1 200 OK\r\nX-Name: café\r\nCONTENT-LENGTH: 0\r\n\r\n");

        assertThat(sent())
                .containsExactly(headers(200, BodyFraming.LENGTH, "x-name", "café", "content-length", "0"), "CLOSE");
    }

    @Test
    void informationalHeadsGoAheadOfTheFinalOne() {
        open(GET);
        serverClose();

        respond("HTTP/1.1 103 Early Hints\r\nLink: </a.css>; rel=preload\r\n\r\n"
                + "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");

        assertThat(sent())
                .containsExactly(
                        "HEADERS 103 [link=</a.css>; rel=preload] -",
                        headers(200, BodyFraming.LENGTH, "content-length", "2"),
                        "DATA ok",
                        "CLOSE");
        assertThat(exchanges.getFirst().status()).isEqualTo(OptionalInt.of(200));
    }

    @Test
    void aCompleteResponseCancelsTheRestOfTheRequestBody() {
        open(request("POST", "/upload", BodyFraming.CHUNKED, "host", "x"));
        serverData("part");

        respond("HTTP/1.1 413 Payload Too Large\r\nContent-Length: 0\r\n\r\n");

        assertThat(sent())
                .containsExactly(
                        headers(413, BodyFraming.LENGTH, "content-length", "0"), "CLOSE", "CLOSE RST CANCELLED");
        assertThat(local.isOpen()).isFalse();
        ExchangeSummary exchange = exchanges.getFirst();
        assertThat(exchange.completed()).isTrue();
        assertThat(exchange.status()).isEqualTo(OptionalInt.of(413));
        assertThat(exchange.requestBytes()).isEqualTo(4);
        assertThat(closedStreams).containsExactly("1 CANCELLED");
    }

    // Broken responses.

    @Test
    void aSwitchingProtocolsResponseIsTheLocalServicesError() {
        open(GET);
        serverClose();

        respond("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: upgrade\r\n\r\n");

        assertLocalServiceError();
    }

    @Test
    void aGarbageStatusLineIsTheLocalServicesError() {
        open(GET);
        serverClose();

        respond("NOT HTTP AT ALL\r\n\r\n");

        assertLocalServiceError();
    }

    @Test
    void aBodyThatEndsShortOfItsLengthIsTheLocalServicesErrorNotAClose() {
        open(GET);
        serverClose();

        respond("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabcd");
        local.close();

        assertThat(sent())
                .containsExactly(
                        headers(200, BodyFraming.LENGTH, "content-length", "10"),
                        "DATA abcd",
                        "CLOSE RST LOCAL_SERVICE_ERROR");
        assertThat(exchanges.getFirst().reset()).contains(ErrorCode.LOCAL_SERVICE_ERROR);
        assertThat(exchanges.getFirst().status()).isEqualTo(OptionalInt.of(200));
    }

    @Test
    void aConnectionThatClosesMidHeadIsTheLocalServicesError() {
        open(GET);
        serverClose();

        respond("HTTP/1.1 200 OK\r\nContent-Le");
        local.close();

        assertLocalServiceError();
    }

    @Test
    void aFinMidHeadIsTheLocalServicesError() {
        open(GET);
        serverClose();

        respond("HTTP/1.1 200 OK\r\nContent-Le");
        local.receiveFin();

        assertLocalServiceError();
    }

    @Test
    void aHeadThatDoesntFitAControlPayloadIsTheLocalServicesError() {
        open(GET);
        serverClose();
        String quotes = "\"".repeat(4_000);

        respond("HTTP/1.1 200 OK\r\nX-A: " + quotes + "\r\nX-B: " + quotes + "\r\nX-C: " + quotes
                + "\r\nContent-Length: 0\r\n\r\n");

        assertLocalServiceError();
    }

    @Test
    void aHeadOverTheDecodersLimitIsTheLocalServicesError() {
        open(GET);
        serverClose();

        respond("HTTP/1.1 200 OK\r\nX-A: " + "a".repeat(13 * 1024) + "\r\n\r\n");

        assertLocalServiceError();
    }

    @Test
    void aTransferCodingOtherThanChunkedIsTheLocalServicesError() {
        open(GET);
        serverClose();

        respond("HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\nxyz");

        assertLocalServiceError();
    }

    @Test
    void aResetFromTheServerCutsTheLocalConnection() {
        open(request("POST", "/", BodyFraming.CHUNKED, "host", "x"));

        connector.onClose(Frame.Close.reset(1, ErrorCode.CANCELLED));

        assertThat(local.isOpen()).isFalse();
        assertThat(exchanges.getFirst().reset()).contains(ErrorCode.CANCELLED);
        assertThat(exchanges.getFirst().status()).isEmpty();
    }

    // What the exchange reports.

    @Test
    void theSummaryHasTheMethodTargetStatusAndBodyBytesButNoHeaderValues() {
        open(request("POST", "/api/items?token=s3cret", BodyFraming.LENGTH, "host", "x", "content-length", "3"));
        serverData("abc");
        serverClose();

        respond("HTTP/1.1 201 Created\r\nSet-Cookie: session=s3cret\r\nContent-Length: 5\r\n\r\nhello");

        assertThat(exchanges).singleElement().satisfies(exchange -> {
            assertThat(exchange.streamId()).isEqualTo(1);
            assertThat(exchange.method()).isEqualTo("POST");
            assertThat(exchange.target()).isEqualTo("/api/items?token=s3cret");
            assertThat(exchange.status()).isEqualTo(OptionalInt.of(201));
            assertThat(exchange.reset()).isEmpty();
            assertThat(exchange.requestBytes()).isEqualTo(3);
            assertThat(exchange.responseBytes()).isEqualTo(5);
            assertThat(exchange.toString()).doesNotContain("session=");
        });
        assertThat(closedStreams).containsExactly("1 -");
    }

    // Helpers.

    private void open(HttpRequestHead head) {
        open(head, HostHeader.KEEP);
    }

    private void open(HttpRequestHead head, HostHeader hostHeader) {
        start(hostHeader);
        local = local();
        pendingDial = control.eventLoop().newSucceededFuture(local);
        connector.open(
                new Frame.Open(1, new Open(TUNNEL_ID, TunnelKind.HTTP, new RemoteAddress("203.0.113.9", 1), head)));
    }

    private void openConnecting(HttpRequestHead head) {
        start(HostHeader.KEEP);
        connecting = control.eventLoop().newPromise();
        pendingDial = connecting;
        connector.open(
                new Frame.Open(1, new Open(TUNNEL_ID, TunnelKind.HTTP, new RemoteAddress("203.0.113.9", 1), head)));
    }

    private void connectNow() {
        local = local();
        connecting.setSuccess(local);
    }

    private void start(HostHeader hostHeader) {
        control.config().setWriteBufferWaterMark(ROOMY);
        ControlConnection connection = new ControlConnection(new ClientSettings(
                URI.create("ws://localhost:7080/v1/connect"),
                "jb_" + "t".repeat(43),
                List.of(TunnelSpec.http("default", TARGET).hostHeader(hostHeader)),
                new TunnelEvents() {
                    @Override
                    public void onExchangeCompleted(ExchangeSummary exchange) {
                        exchanges.add(exchange);
                    }

                    @Override
                    public void onStreamClosed(
                            int streamId, long bytesIn, long bytesOut, long durationMillis, Optional<ErrorCode> reset) {
                        closedStreams.add(
                                streamId + " " + reset.map(ErrorCode::code).orElse("-"));
                    }
                },
                new ClientInfo("jburrow-test", "1.0.0", null, null),
                Optional.empty(),
                ClientSettings.DEFAULT_HANDSHAKE_TIMEOUT,
                ClientSettings.DEFAULT_WINDOWS));
        ClientSession session = connection.session();
        session.onUpgraded();
        session.onServerHello(new ServerHello(
                SESSION_ID,
                new ServerInfo("jburrow-server", "0.3.0"),
                List.of("http"),
                new SessionLimits(65_536, 1, 100, 15_000, 45_000),
                ClientSettings.DEFAULT_WINDOWS));
        session.onRegistered(new Registered(
                List.of(RegisteredTunnel.http("default", TUNNEL_ID, "https", "quiet-otter-4f2a.example.dev", 443))));
        connector = new LocalConnector(connection, control, target -> pendingDial, () -> 0L);
    }

    /** A local connection as the dialer hands it over: not reading, no handlers. */
    private static DuplexEmbeddedChannel local() {
        DuplexEmbeddedChannel channel = new DuplexEmbeddedChannel(LOCAL);
        channel.config().setAutoRead(false);
        return channel;
    }

    private void serverData(String text) {
        connector.onData(new Frame.Data(1, Unpooled.copiedBuffer(text, StandardCharsets.ISO_8859_1)));
        connector.onReadComplete();
    }

    private void serverClose() {
        connector.onClose(Frame.Close.half(1));
    }

    /** One read batch from the local service, as its event loop delivers it. */
    private void respond(String bytes) {
        local.pipeline().fireChannelRead(Unpooled.copiedBuffer(bytes, StandardCharsets.ISO_8859_1));
        local.pipeline().fireChannelReadComplete();
    }

    /** What the connector has written to the local service since the last call. */
    private String written() {
        StringBuilder text = new StringBuilder();
        for (Object out = local.readOutbound(); out != null; out = local.readOutbound()) {
            if (out instanceof ByteBuf bytes) {
                text.append(bytes.toString(StandardCharsets.ISO_8859_1));
            }
            ReferenceCountUtil.release(out);
        }
        return text.toString();
    }

    /** The frames sent to the server since the last call, one line each, releasing them. */
    private List<String> sent() {
        List<String> frames = new ArrayList<>();
        for (Object out = control.readOutbound(); out != null; out = control.readOutbound()) {
            switch (out) {
                case Frame.Headers headers -> frames.add(describe(headers.head()));
                case Frame.Data data -> frames.add("DATA " + data.payload().toString(StandardCharsets.ISO_8859_1));
                case Frame.Close close ->
                    frames.add(close.reset()
                            .map(code -> "CLOSE RST " + code.code())
                            .orElse("CLOSE"));
                case Frame.WindowUpdate ignored -> {}
                default -> frames.add(out.toString());
            }
            ReferenceCountUtil.release(out);
        }
        return frames;
    }

    private void assertLocalServiceError() {
        assertThat(sent()).last().isEqualTo("CLOSE RST LOCAL_SERVICE_ERROR");
        assertThat(sent()).isEmpty();
        assertThat(local.isOpen()).isFalse();
        assertThat(exchanges).singleElement().satisfies(exchange -> {
            assertThat(exchange.completed()).isFalse();
            assertThat(exchange.reset()).contains(ErrorCode.LOCAL_SERVICE_ERROR);
        });
    }

    private static String headers(int status, BodyFraming body, String... namesAndValues) {
        return describe(new HttpResponseHead(status, HeaderFields.of(namesAndValues), body));
    }

    private static String describe(HttpResponseHead head) {
        List<String> fields = head.headers().fields().stream()
                .map(field -> field.name() + "=" + field.value())
                .toList();
        return "HEADERS " + head.status() + " " + fields + " "
                + (head.body() == null ? "-" : head.body().wireName());
    }

    private static HttpRequestHead request(String method, String target, BodyFraming body, String... namesAndValues) {
        return new HttpRequestHead(method, target, HeaderFields.of(namesAndValues), body);
    }
}
