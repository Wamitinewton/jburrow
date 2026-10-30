package io.jburrow.server.core.tunnel.adapter.netty.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EdgeErrorPageTest {

    private static final Map<EdgeError, Integer> STATUS = Map.of(
            EdgeError.TUNNEL_NOT_FOUND, 404,
            EdgeError.BAD_REQUEST, 400,
            EdgeError.REQUEST_TOO_LARGE, 413,
            EdgeError.REQUEST_HEADERS_TOO_LARGE, 431,
            EdgeError.UPGRADE_NOT_SUPPORTED, 501,
            EdgeError.LOCAL_SERVICE_UNREACHABLE, 502,
            EdgeError.LOCAL_SERVICE_ERROR, 502,
            EdgeError.TUNNEL_BUSY, 503,
            EdgeError.SERVER_SHUTTING_DOWN, 503,
            EdgeError.RESPONSE_TIMEOUT, 504);

    private final EdgeErrorPage pages = new EdgeErrorPage();

    @ParameterizedTest
    @EnumSource(EdgeError.class)
    void everyCodeHasItsStatusAndHeaders(EdgeError error) {
        FullHttpResponse response = pages.render(error, "quiet-otter.example.dev", false, false);
        try {
            HttpHeaders headers = response.headers();
            assertThat(response.status().code()).isEqualTo(STATUS.get(error));
            assertThat(headers.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("text/html; charset=utf-8");
            assertThat(headers.get(HttpHeaderNames.CACHE_CONTROL)).isEqualTo("no-store");
            assertThat(headers.get(EdgeErrorPage.JBURROW_ERROR)).isEqualTo(error.name());
            assertThat(headers.getInt(HttpHeaderNames.CONTENT_LENGTH))
                    .isEqualTo(response.content().readableBytes());
            assertThat(headers.contains(HttpHeaderNames.CONNECTION)).isFalse();
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertThat(body)
                    .contains(
                            "quiet-otter.example.dev",
                            error.name(),
                            escaped(error.explanation()),
                            escaped(error.suggestion()))
                    .contains(Integer.toString(error.status()));
        } finally {
            response.release();
        }
    }

    @Test
    void onlyTheBusyAndShutdownPagesCarryRetryAfter() {
        for (EdgeError error : EdgeError.values()) {
            FullHttpResponse response = pages.render(error, "", false, false);
            String retryAfter = response.headers().get(HttpHeaderNames.RETRY_AFTER);
            response.release();
            switch (error) {
                case TUNNEL_BUSY -> assertThat(retryAfter).isEqualTo("1");
                case SERVER_SHUTTING_DOWN -> assertThat(retryAfter).isEqualTo("5");
                default -> assertThat(retryAfter).as(error.name()).isNull();
            }
        }
    }

    @Test
    void aClosingPageSaysSo() {
        FullHttpResponse response = pages.render(EdgeError.BAD_REQUEST, "", false, true);

        assertThat(response.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo("close");
        response.release();
    }

    @Test
    void aHeadRequestGetsTheHeadersWithoutTheBody() {
        FullHttpResponse get = pages.render(EdgeError.TUNNEL_NOT_FOUND, "a.example.dev", false, false);
        FullHttpResponse head = pages.render(EdgeError.TUNNEL_NOT_FOUND, "a.example.dev", true, false);

        assertThat(head.content().readableBytes()).isZero();
        assertThat(head.headers().getInt(HttpHeaderNames.CONTENT_LENGTH))
                .isEqualTo(get.content().readableBytes());
        assertThat(head.headers().get(EdgeErrorPage.JBURROW_ERROR)).isEqualTo("TUNNEL_NOT_FOUND");
        get.release();
        head.release();
    }

    @Test
    void theHostIsEscaped() {
        FullHttpResponse response =
                pages.render(EdgeError.TUNNEL_NOT_FOUND, "<script>alert('x')</script>&\"é{{code}}", false, false);

        String body = response.content().toString(StandardCharsets.UTF_8);
        response.release();
        assertThat(body)
                .doesNotContain("<script>")
                .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;&amp;&quot;&#233;{{code}}");
    }

    @Test
    void theTemplateIsSmallAndLoadsNothing() throws IOException {
        String template;
        try (InputStream in = EdgeErrorPage.class.getResourceAsStream(EdgeErrorPage.TEMPLATE)) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(template.getBytes(StandardCharsets.UTF_8)).hasSizeLessThan(2_048);
        assertThat(template.toLowerCase())
                .doesNotContain("<script", "<link", "<img", "src=", "href=", "url(", "@import", "http:", "https:");
    }

    private static String escaped(String text) {
        StringBuilder out = new StringBuilder();
        EdgeErrorPage.escape(text, out);
        return out.toString();
    }

    @Test
    void anUnknownPlaceholderIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EdgeErrorPage("<p>{{path}}</p>"));
        assertThatIllegalArgumentException().isThrownBy(() -> new EdgeErrorPage("<p>{{host</p>"));
    }
}
