package io.jburrow.server.core.tunnel.adapter.netty.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.control.Open;
import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.TunnelKind;
import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §An exchange, steps 1 and 3, and §Request headers the edge sets.
 */
class RequestHeadsTest {

    private static final long MAX_BODY = 1_000;
    private static final UUID TUNNEL = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final RemoteAddress VISITOR = new RemoteAddress("203.0.113.9", 0);
    private static final String HOST = "quiet-otter.example.dev";

    private final RequestHeads heads = new RequestHeads(ControlJson.create(), MAX_BODY);

    @Test
    void theOpenCarriesTheTunnelTheVisitorAndTheHead() {
        HttpRequest request = request(HttpMethod.POST, "/api/items?page=2", "Host", HOST, "Content-Length", "27");

        Open open = forward(request, false);

        assertThat(open.tunnelId()).isEqualTo(TUNNEL);
        assertThat(open.kind()).isEqualTo(TunnelKind.HTTP);
        assertThat(open.remote()).isEqualTo(VISITOR);
        assertThat(open.request().method()).isEqualTo("POST");
        assertThat(open.request().target()).isEqualTo("/api/items?page=2");
        assertThat(open.request().body()).isEqualTo(BodyFraming.LENGTH);
    }

    @Test
    void connectionSpecificFieldsTheNamesConnectionListsAndExpectAreRemoved() {
        HttpRequest request = request(
                HttpMethod.POST,
                "/",
                "Host",
                HOST,
                "Connection",
                "keep-alive, X-Hop",
                "Keep-Alive",
                "timeout=5",
                "Proxy-Connection",
                "keep-alive",
                "TE",
                "trailers",
                "Trailer",
                "x-checksum",
                "X-Hop",
                "secret-to-the-proxy",
                "Expect",
                "100-continue",
                "Content-Length",
                "3",
                "Accept",
                "*/*");

        HeaderFields fields = forward(request, false).request().headers();

        assertThat(names(fields))
                .containsExactly(
                        "host",
                        "content-length",
                        "accept",
                        "x-forwarded-for",
                        "x-forwarded-proto",
                        "x-forwarded-host",
                        "via");
    }

    @Test
    void everyForwardingFieldTheVisitorSentIsReplacedByTheEdgesOwn() {
        HttpRequest request = request(
                HttpMethod.GET,
                "/",
                "Host",
                HOST + ":7080",
                "X-Forwarded-For",
                "198.51.100.1",
                "X-Forwarded-Proto",
                "https",
                "X-Forwarded-Host",
                "bank.example",
                "X-Forwarded-Port",
                "443",
                "X-Real-IP",
                "198.51.100.1",
                "Forwarded",
                "for=198.51.100.1",
                "Via",
                "1.1 corporate-proxy");

        HeaderFields fields = forward(request, false).request().headers();

        assertThat(fields.fields())
                .containsExactly(
                        new HeaderFields.Field("host", HOST + ":7080"),
                        new HeaderFields.Field("via", "1.1 corporate-proxy"),
                        new HeaderFields.Field("x-forwarded-for", "203.0.113.9"),
                        new HeaderFields.Field("x-forwarded-proto", "http"),
                        new HeaderFields.Field("x-forwarded-host", HOST + ":7080"),
                        new HeaderFields.Field("via", "1.1 jburrow"));
    }

    @ParameterizedTest
    @CsvSource({"true, https, https", "true, HTTPS, https", "true, http, http", "true, ftp, http", "false, https, http"
    })
    void theSchemeIsTheTrustedProxysWhenItNamesOne(boolean trusted, String sent, String expected) {
        HttpRequest request = request(HttpMethod.GET, "/", "Host", HOST, "X-Forwarded-Proto", sent);

        HeaderFields fields = forward(request, trusted).request().headers();

        assertThat(fields.single("x-forwarded-proto")).contains(expected);
    }

    @Test
    void namesAreLowerCasedAndValuesKeptAsSentIncludingRepeatsAndIsoLatin1() {
        HttpRequest request =
                request(HttpMethod.GET, "/", "Host", HOST, "X-Name", "café", "Cookie", "a=1", "Cookie", "b=2");

        HeaderFields fields = forward(request, false).request().headers();

        assertThat(fields.all("x-name")).containsExactly("café");
        assertThat(fields.all("cookie")).containsExactly("a=1", "b=2");
    }

    @ParameterizedTest
    @CsvSource({"'', EMPTY", "0, EMPTY", "5, LENGTH"})
    void theBodyIsFramedByItsLength(String length, BodyFraming expected) {
        HttpRequest request = length.isEmpty()
                ? request(HttpMethod.POST, "/", "Host", HOST)
                : request(HttpMethod.POST, "/", "Host", HOST, "Content-Length", length);

        HttpRequestHead head = forward(request, false).request();

        assertThat(head.body()).isEqualTo(expected);
        assertThat(head.headers().all("content-length")).isEqualTo(length.isEmpty() ? List.of() : List.of(length));
    }

    @Test
    void aChunkedBodyIsChunkedWithoutItsTransferEncoding() {
        HttpRequestHead head = forward(
                        request(HttpMethod.POST, "/", "Host", HOST, "Transfer-Encoding", "chunked"), false)
                .request();

        assertThat(head.body()).isEqualTo(BodyFraming.CHUNKED);
        assertThat(head.headers().all("transfer-encoding")).isEmpty();
    }

    @Test
    void theEdgeRefusesWhatItWontForward() {
        assertThat(check(request(HttpMethod.GET, "/", "Host", HOST, "Upgrade", "websocket")))
                .contains(EdgeError.UPGRADE_NOT_SUPPORTED);
        assertThat(check(request(HttpMethod.CONNECT, "/", "Host", HOST))).contains(EdgeError.BAD_REQUEST);
        assertThat(check(request(HttpMethod.GET, "http://" + HOST + "/", "Host", HOST)))
                .contains(EdgeError.BAD_REQUEST);
        assertThat(check(request(HttpMethod.GET, "*", "Host", HOST))).contains(EdgeError.BAD_REQUEST);
        assertThat(check(request(HttpMethod.OPTIONS, "*", "Host", HOST))).isEmpty();
    }

    @Test
    void ambiguousFramingIsRefused() {
        assertThat(check(request(HttpMethod.POST, "/", "Host", HOST, "Transfer-Encoding", "gzip, chunked")))
                .contains(EdgeError.BAD_REQUEST);
        assertThat(check(request(HttpMethod.POST, "/", "Host", HOST, "Transfer-Encoding", "gzip")))
                .contains(EdgeError.BAD_REQUEST);
        assertThat(check(request(
                        HttpMethod.POST,
                        "/",
                        "Host",
                        HOST,
                        "Transfer-Encoding",
                        "chunked",
                        "Transfer-Encoding",
                        "chunked")))
                .contains(EdgeError.BAD_REQUEST);
        assertThat(check(request(
                        HttpMethod.POST, "/", "Host", HOST, "Transfer-Encoding", "chunked", "Content-Length", "3")))
                .contains(EdgeError.BAD_REQUEST);
        assertThat(check(request(HttpMethod.POST, "/", "Host", HOST, "Content-Length", "3", "Content-Length", "3")))
                .contains(EdgeError.BAD_REQUEST);
        assertThat(check(request(HttpMethod.POST, "/", "Host", HOST, "Transfer-Encoding", "Chunked")))
                .isEmpty();
    }

    @Test
    void aDeclaredBodyOverTheLimitIsTooLarge() {
        assertThat(check(request(HttpMethod.POST, "/", "Host", HOST, "Content-Length", "1001")))
                .contains(EdgeError.REQUEST_TOO_LARGE);
        assertThat(check(request(HttpMethod.POST, "/", "Host", HOST, "Content-Length", "99999999999999999999")))
                .contains(EdgeError.REQUEST_TOO_LARGE);
        assertThat(check(request(HttpMethod.POST, "/", "Host", HOST, "Content-Length", "1000")))
                .isEmpty();
    }

    @Test
    void aHeadThatDoesntFitIsRefusedAsTooLarge() {
        String value = "v".repeat(RequestHeads.MAX_VALUE_LENGTH);
        HttpRequest longValue = request(HttpMethod.GET, "/", "Host", HOST, "X-Big", value + "v");
        HttpRequest pastThePayload =
                request(HttpMethod.GET, "/", "Host", HOST, "X-A", value, "X-B", value, "X-C", value, "X-D", value);
        HttpRequest tooMany = request(HttpMethod.GET, "/", "Host", HOST);
        for (int i = 0; i < HeaderFields.MAX_FIELDS; i++) {
            tooMany.headers().add("X-Field-" + i, "1");
        }

        assertThat(refusal(longValue)).isEqualTo(EdgeError.REQUEST_HEADERS_TOO_LARGE);
        assertThat(refusal(pastThePayload)).isEqualTo(EdgeError.REQUEST_HEADERS_TOO_LARGE);
        assertThat(refusal(tooMany)).isEqualTo(EdgeError.REQUEST_HEADERS_TOO_LARGE);
    }

    @Test
    void aHeadBreakingAFieldRuleIsABadRequest() {
        HttpRequest controlCharacter = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                "/",
                DefaultHttpHeadersFactory.headersFactory().withValidation(false));
        controlCharacter.headers().add("Host", HOST).add("X-Bad", "a\u007fb");
        HttpRequest nonAsciiTarget = request(HttpMethod.GET, "/café", "Host", HOST);

        assertThat(refusal(controlCharacter)).isEqualTo(EdgeError.BAD_REQUEST);
        assertThat(refusal(nonAsciiTarget)).isEqualTo(EdgeError.BAD_REQUEST);
    }

    private Open forward(HttpRequest request, boolean viaTrustedProxy) {
        assertThat(heads.check(request)).isEmpty();
        RequestHeads.Result result = heads.open(request, TUNNEL, VISITOR, viaTrustedProxy);
        assertThat(result).isInstanceOf(RequestHeads.Result.Forward.class);
        return ((RequestHeads.Result.Forward) result).open();
    }

    private Optional<EdgeError> check(HttpRequest request) {
        return heads.check(request);
    }

    private EdgeError refusal(HttpRequest request) {
        RequestHeads.Result result = heads.open(request, TUNNEL, VISITOR, false);
        assertThat(result).isInstanceOf(RequestHeads.Result.Refuse.class);
        return ((RequestHeads.Result.Refuse) result).error();
    }

    private static List<String> names(HeaderFields fields) {
        return fields.fields().stream().map(HeaderFields.Field::name).toList();
    }

    private static HttpRequest request(HttpMethod method, String target, String... namesAndValues) {
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, target);
        for (int i = 0; i < namesAndValues.length; i += 2) {
            request.headers().add(namesAndValues[i], namesAndValues[i + 1]);
        }
        return request;
    }
}
