package io.jburrow.server.core.tunnel.adapter.netty.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.control.UpgradeRefusal;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolVersion;
import io.jburrow.server.core.tunnel.application.HandshakeTicket;
import io.jburrow.server.core.tunnel.application.RecordingTunnelMetrics;
import io.jburrow.server.core.tunnel.application.SessionAdmission;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConnectRequestFilterTest {

    private static final ControlJson JSON = ControlJson.create();
    private static final Instant NOW = Instant.parse("2026-10-25T09:30:00Z");
    private static final String CONNECT_HOST = "connect.example.dev";

    private final SessionAdmission admission = new SessionAdmission(2, 1, 3);
    private final RecordingTunnelMetrics metrics = new RecordingTunnelMetrics();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final ConnectRequestFilter filter = new ConnectRequestFilter(
            CONNECT_HOST,
            admission,
            shuttingDown::get,
            metrics,
            new UpgradeErrorResponses(JSON, InstantSource.fixed(NOW)));

    private final PeerChannel channel = newChannel();

    @AfterEach
    void closeChannel() {
        channel.finishAndReleaseAll();
    }

    @Test
    void aValidConnectRequestPassesWithATicket() {
        FullHttpRequest request = connectRequest();

        channel.writeInbound(request);

        FullHttpRequest passed = channel.readInbound();
        assertThat(passed).isSameAs(request);
        assertThat(passed.refCnt()).isOne();
        passed.release();
        assertThat(channel.isOpen()).isTrue();
        assertThat(ConnectRequestFilter.ticket(channel)).isNotNull();
        assertThat(admission.activeSessions()).isOne();
        assertThat(admission.handshaking()).isOne();
        assertThat(metrics.events()).isEmpty();
    }

    @Test
    void theTicketIsReleasedWhenTheChannelCloses() {
        channel.writeInbound(connectRequest());
        releaseInbound();
        HandshakeTicket ticket = ConnectRequestFilter.ticket(channel);

        channel.close();

        assertThat(ticket).isNotNull();
        assertThat(admission.activeSessions()).isZero();
        assertThat(admission.handshaking()).isZero();
    }

    @Test
    void aMethodOtherThanGetIsNotFound() {
        FullHttpRequest request = connectRequest();
        request.setMethod(HttpMethod.POST);

        assertRefused(request, ErrorCode.NOT_FOUND, 404);
    }

    @Test
    void anotherPathIsNotFound() {
        assertRefused(request("/", headers -> {}), ErrorCode.NOT_FOUND, 404);
        assertRefused(request("/v1/connect/", headers -> {}), ErrorCode.NOT_FOUND, 404);
        assertRefused(request("/v1/%63onnect", headers -> {}), ErrorCode.NOT_FOUND, 404);
    }

    @Test
    void aQueryStringIsIgnored() {
        channel.writeInbound(request("/v1/connect?client=x", headers -> {}));

        FullHttpRequest passed = channel.readInbound();
        assertThat(passed).isNotNull();
        passed.release();
    }

    @Test
    void anotherHostIsNotFound() {
        assertRefused(
                request("/v1/connect", headers -> headers.set(HttpHeaderNames.HOST, "api.example.dev")),
                ErrorCode.NOT_FOUND,
                404);
    }

    @Test
    void aMissingOrRepeatedHostIsNotFound() {
        assertRefused(
                request("/v1/connect", headers -> headers.remove(HttpHeaderNames.HOST)), ErrorCode.NOT_FOUND, 404);
        assertRefused(
                request("/v1/connect", headers -> headers.add(HttpHeaderNames.HOST, CONNECT_HOST)),
                ErrorCode.NOT_FOUND,
                404);
    }

    @Test
    void theHostIsComparedWithoutAsciiCaseOrPort() {
        for (String host : List.of("CONNECT.Example.DEV", "connect.example.dev:443", "Connect.example.dev:7080")) {
            PeerChannel fresh = newChannel();
            fresh.writeInbound(request("/v1/connect", headers -> headers.set(HttpHeaderNames.HOST, host)));

            FullHttpRequest passed = fresh.readInbound();
            assertThat(passed).as(host).isNotNull();
            passed.release();
            fresh.finishAndReleaseAll();
        }
    }

    @Test
    void theHostComparisonIsAsciiOnly() {
        // U+212A KELVIN SIGN lower-cases to 'k' in Java, but is not ASCII.
        PeerChannel edge = new PeerChannel(
                new InetSocketAddress("127.0.0.1", 40_000),
                new ClientAddressResolver(List.of()),
                new ConnectRequestFilter(
                        "k.example.dev",
                        admission,
                        () -> false,
                        metrics,
                        new UpgradeErrorResponses(JSON, InstantSource.fixed(NOW))));

        edge.writeInbound(request("/v1/connect", headers -> headers.set(HttpHeaderNames.HOST, "K.example.dev")));

        FullHttpResponse response = edge.readOutbound();
        assertThat(response.status().code()).isEqualTo(404);
        response.release();
        edge.finishAndReleaseAll();
    }

    @Test
    void anOriginIsForbidden() {
        assertRefused(
                request("/v1/connect", headers -> headers.set(HttpHeaderNames.ORIGIN, "https://evil.example")),
                ErrorCode.FORBIDDEN_ORIGIN,
                403);
    }

    @Test
    void anUnsupportedVersionListsWhatIsSupported() {
        UpgradeRefusal refusal = assertRefused(
                request(
                        "/v1/connect",
                        headers -> headers.set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "jburrow.v1-draft.0")),
                ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                400);

        assertThat(refusal.supported()).isEqualTo(ProtocolVersion.supported());
        assertThat(refusal.message()).contains(ProtocolVersion.CURRENT);
    }

    @Test
    void noSubprotocolIsAnUnsupportedVersion() {
        assertRefused(
                request("/v1/connect", headers -> headers.remove(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL)),
                ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                400);
    }

    @Test
    void aSupportedVersionAnywhereInTheOfferPasses() {
        channel.writeInbound(request(
                "/v1/connect",
                headers -> headers.set(
                        HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "jburrow.v1-draft.9, " + ProtocolVersion.CURRENT)));

        FullHttpRequest passed = channel.readInbound();
        assertThat(passed).isNotNull();
        passed.release();
    }

    @Test
    void whileShuttingDownTheServerRefusesWithRetryAfter() {
        shuttingDown.set(true);

        assertRefused(connectRequest(), ErrorCode.SERVER_SHUTTING_DOWN, 503);

        assertThat(admission.activeSessions()).isZero();
    }

    @Test
    void atTheHandshakeCapTheServerRefusesWithRetryAfter() {
        channel.writeInbound(connectRequest());
        releaseInbound();

        assertRefused(connectRequest(), ErrorCode.SERVER_AT_CAPACITY, 503);

        assertThat(admission.activeSessions()).isOne();
    }

    @Test
    void anInvalidOpeningHandshakeIsAProtocolErrorAndReleasesItsTicket() {
        List<Consumer<HttpHeaders>> breakages = List.of(
                headers -> headers.remove(HttpHeaderNames.UPGRADE),
                headers -> headers.set(HttpHeaderNames.UPGRADE, "h2c"),
                headers -> headers.set(HttpHeaderNames.CONNECTION, "keep-alive"),
                headers -> headers.remove(HttpHeaderNames.SEC_WEBSOCKET_KEY),
                headers -> headers.set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "c2hvcnQ="),
                headers -> headers.set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "not base64 at all!"),
                headers -> headers.set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "8"));
        for (Consumer<HttpHeaders> breakage : breakages) {
            assertRefused(request("/v1/connect", breakage), ErrorCode.PROTOCOL_ERROR, 400);

            assertThat(admission.activeSessions()).isZero();
        }
    }

    @Test
    void upgradeAndConnectionAreCaseInsensitiveTokenLists() {
        channel.writeInbound(request("/v1/connect", headers -> {
            headers.set(HttpHeaderNames.UPGRADE, "WebSocket");
            headers.set(HttpHeaderNames.CONNECTION, "keep-alive, Upgrade");
        }));

        FullHttpRequest passed = channel.readInbound();
        assertThat(passed).isNotNull();
        passed.release();
    }

    @Test
    void aRequestNettyCouldNotDecodeIsAProtocolError() {
        FullHttpRequest request = connectRequest();
        request.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("header too long")));

        assertRefused(request, ErrorCode.PROTOCOL_ERROR, 400);
    }

    @Test
    void theFirstFailingCheckWins() {
        FullHttpRequest request = request("/v1/connect", headers -> {
            headers.set(HttpHeaderNames.ORIGIN, "https://evil.example");
            headers.set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "jburrow.v1-draft.0");
            headers.remove(HttpHeaderNames.SEC_WEBSOCKET_KEY);
        });
        shuttingDown.set(true);

        assertRefused(request, ErrorCode.FORBIDDEN_ORIGIN, 403);
    }

    @Test
    void stripsThePortFromAHost() {
        assertThat(ConnectRequestFilter.withoutPort("connect.example.dev:443")).isEqualTo("connect.example.dev");
        assertThat(ConnectRequestFilter.withoutPort("connect.example.dev")).isEqualTo("connect.example.dev");
        assertThat(ConnectRequestFilter.withoutPort("[::1]:7080")).isEqualTo("[::1]");
        assertThat(ConnectRequestFilter.withoutPort("[::1]")).isEqualTo("[::1]");
        assertThat(ConnectRequestFilter.withoutPort("::1")).isEqualTo("::1");
    }

    private UpgradeRefusal assertRefused(FullHttpRequest request, ErrorCode code, int status) {
        PeerChannel fresh = newChannel();
        int refusalsBefore = metrics.events().size();

        fresh.writeInbound(request);

        assertThat((Object) fresh.readInbound()).as("passed on").isNull();
        assertThat(request.refCnt()).as("request released").isZero();
        FullHttpResponse response = fresh.readOutbound();
        try {
            assertThat(response.status().code()).isEqualTo(status);
            HttpHeaders headers = response.headers();
            assertThat(headers.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("application/json");
            assertThat(headers.get(HttpHeaderNames.CONNECTION)).isEqualTo("close");
            assertThat(headers.get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
            assertThat(headers.getInt(HttpHeaderNames.CONTENT_LENGTH))
                    .isEqualTo(response.content().readableBytes());
            boolean retryable = code == ErrorCode.SERVER_AT_CAPACITY || code == ErrorCode.SERVER_SHUTTING_DOWN;
            assertThat(headers.get(HttpHeaderNames.RETRY_AFTER)).isEqualTo(retryable ? "5" : null);

            UpgradeRefusal refusal = JSON.readRefusal(ByteBufUtil.getBytes(response.content()));
            assertThat(refusal.success()).isFalse();
            assertThat(refusal.code()).isEqualTo(code);
            assertThat(refusal.message()).isNotBlank();
            assertThat(refusal.timestamp()).isEqualTo("2026-10-25T09:30:00Z");
            assertThat(refusal.path()).isEqualTo(ConnectRequestFilter.pathOf(request.uri()));
            if (code != ErrorCode.UNSUPPORTED_PROTOCOL_VERSION) {
                assertThat(refusal.supported()).isNull();
            }
            assertThat(fresh.isOpen()).as("closed after the refusal").isFalse();
            assertThat(metrics.events().subList(refusalsBefore, metrics.events().size()))
                    .containsExactly("handshakeFailed " + code);
            return refusal;
        } finally {
            response.release();
            fresh.finishAndReleaseAll();
        }
    }

    private void releaseInbound() {
        FullHttpRequest passed = channel.readInbound();
        passed.release();
    }

    private PeerChannel newChannel() {
        return new PeerChannel(
                new InetSocketAddress("127.0.0.1", 40_000), new ClientAddressResolver(List.of()), filter);
    }

    private static FullHttpRequest connectRequest() {
        return request("/v1/connect", headers -> {});
    }

    private static FullHttpRequest request(String uri, Consumer<HttpHeaders> change) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
        HttpHeaders headers = request.headers();
        headers.set(HttpHeaderNames.HOST, CONNECT_HOST);
        headers.set(HttpHeaderNames.UPGRADE, "websocket");
        headers.set(HttpHeaderNames.CONNECTION, "Upgrade");
        headers.set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        headers.set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        headers.set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, ProtocolVersion.CURRENT);
        change.accept(headers);
        return request;
    }
}
