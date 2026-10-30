package io.jburrow.server.core.tunnel.adapter.netty.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpResponseHead;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Response headers the edge changes. */
class ResponseHeadsTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "sid=1; Domain=example.dev; Path=/   | sid=1; Path=/",
                "sid=1; Path=/; domain=.example.dev  | sid=1; Path=/",
                "sid=1;DOMAIN=example.dev;Secure     | sid=1;Secure",
                "sid=1; Domain = example.dev         | sid=1",
                "sid=1; Domain; HttpOnly             | sid=1; HttpOnly",
                "domain=example.dev; Path=/          | domain=example.dev; Path=/",
                "sid=1; Path=/domain; Max-Age=60     | sid=1; Path=/domain; Max-Age=60",
                "sid=1; Domain=a; Domain=b; Secure   | sid=1; Secure",
            })
    void removesEveryDomainAttributeAndNothingElse(String cookie, String expected) {
        assertThat(ResponseHeads.withoutDomain(cookie.strip())).isEqualTo(expected.strip());
    }

    @Test
    void everySetCookieLosesItsDomainAndOtherFieldsKeepTheirOrder() {
        HttpResponseHead head = new HttpResponseHead(
                200,
                HeaderFields.of(
                        "set-cookie", "a=1; Domain=example.dev",
                        "content-type", "text/plain",
                        "set-cookie", "b=2; Path=/; Domain=example.dev",
                        "content-length", "2"),
                BodyFraming.LENGTH);

        HttpResponse response = ResponseHeads.build(head, false, true, false);

        assertThat(response.headers().getAll("set-cookie")).containsExactly("a=1", "b=2; Path=/");
        assertThat(response.headers().names())
                .containsExactlyInAnyOrder("set-cookie", "content-type", "content-length");
        assertThat(response.headers().get("content-type")).isEqualTo("text/plain");
        assertThat(response).isNotInstanceOf(FullHttpResponse.class);
    }

    @Test
    void framingComesFromTheBody() {
        HttpResponse length = ResponseHeads.build(ok(BodyFraming.LENGTH, "content-length", "5"), false, true, false);
        HttpResponse chunked = ResponseHeads.build(ok(BodyFraming.CHUNKED), false, true, false);
        HttpResponse empty = ResponseHeads.build(ok(BodyFraming.EMPTY), false, true, false);

        assertThat(length.headers().get("content-length")).isEqualTo("5");
        assertThat(length.headers().contains("transfer-encoding")).isFalse();
        assertThat(chunked.headers().get("transfer-encoding")).isEqualTo("chunked");
        assertThat(chunked.headers().contains("content-length")).isFalse();
        assertThat(empty.headers().get("content-length")).isEqualTo("0");
    }

    @Test
    void aChunkedBodyToAVisitorThatCantTakeChunksEndsWithTheConnection() {
        HttpResponseHead head = ok(BodyFraming.CHUNKED);

        HttpResponse response = ResponseHeads.build(head, false, false, true);

        assertThat(ResponseHeads.closeDelimited(head, false)).isTrue();
        assertThat(ResponseHeads.closeDelimited(head, true)).isFalse();
        assertThat(response.headers().contains("transfer-encoding")).isFalse();
        assertThat(response.headers().get("connection")).isEqualTo("close");
    }

    @Test
    void aResponseToHeadAndANotModifiedKeepTheirContentLength() {
        HttpResponse head = ResponseHeads.build(ok(BodyFraming.EMPTY, "content-length", "1043"), true, true, false);
        HttpResponse notModified = ResponseHeads.build(
                new HttpResponseHead(304, HeaderFields.of("content-length", "1043"), BodyFraming.EMPTY),
                false,
                true,
                false);

        assertThat(head.headers().get("content-length")).isEqualTo("1043");
        assertThat(notModified.headers().get("content-length")).isEqualTo("1043");
    }

    @Test
    void aNoContentResponseHasNoContentLength() {
        HttpResponse response = ResponseHeads.build(
                new HttpResponseHead(204, HeaderFields.of(), BodyFraming.EMPTY), false, true, false);

        assertThat(response.status().code()).isEqualTo(204);
        assertThat(response.headers().contains("content-length")).isFalse();
    }

    @Test
    void anInformationalHeadIsAWholeResponseWithItsFieldsAndNoFraming() {
        HttpResponse hints = ResponseHeads.build(
                new HttpResponseHead(103, HeaderFields.of("link", "</app.css>; rel=preload"), null), false, true, true);

        assertThat(hints).isInstanceOf(FullHttpResponse.class);
        assertThat(hints.status().code()).isEqualTo(103);
        assertThat(hints.headers().getAll("link")).isEqualTo(List.of("</app.css>; rel=preload"));
        assertThat(hints.headers().contains("connection")).isFalse();
        assertThat(hints.headers().contains("content-length")).isFalse();
    }

    @Test
    void valuesKeepTheirIsoLatin1Characters() {
        HttpResponse response = ResponseHeads.build(
                ok(BodyFraming.EMPTY, "x-name", "café", "set-cookie", "n=ÿ; Domain=x"), false, true, false);

        assertThat(response.headers().get("x-name")).isEqualTo("café");
        assertThat(response.headers().get("set-cookie")).isEqualTo("n=ÿ");
    }

    private static HttpResponseHead ok(BodyFraming body, String... fields) {
        return new HttpResponseHead(200, HeaderFields.of(fields), body);
    }
}
