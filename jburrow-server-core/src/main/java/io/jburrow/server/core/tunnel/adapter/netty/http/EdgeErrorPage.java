package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Renders the page the edge answers with when it can't complete an exchange: {@code error-page.html} beside this
 * class, filled in with the host, the code and its two sentences. The host is the only value a visitor chooses, and
 * it is HTML-escaped; the request's path never appears.
 *
 * <p>The template is split once, so a value is never searched for placeholders and can't inject one.
 */
public final class EdgeErrorPage {

    /** The header carrying the page's code, so a script or a log can tell the edge's answer from the tunnel's. */
    public static final AsciiString JBURROW_ERROR = AsciiString.cached("jburrow-error");

    static final String TEMPLATE = "error-page.html";

    private static final AsciiString TEXT_HTML_UTF8 = AsciiString.cached("text/html; charset=utf-8");
    private static final String OPEN = "{{";
    private static final String CLOSE = "}}";
    private static final Set<String> PLACEHOLDERS =
            Set.of("status", "reason", "host", "explanation", "suggestion", "code");

    /** Literal text and placeholder names, alternating, starting with literal text. */
    private final List<String> parts;

    public EdgeErrorPage() {
        this(load());
    }

    EdgeErrorPage(String template) {
        this.parts = split(template);
    }

    /**
     * The page for {@code error}, as a complete response.
     *
     * @param host the host the visitor asked for, as the edge parsed it; empty when it had none
     * @param head whether the request was {@code HEAD}: the headers are the same, and there is no body
     * @param close whether the edge closes the connection after the page
     */
    public FullHttpResponse render(EdgeError error, String host, boolean head, boolean close) {
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(host, "host");
        byte[] body = body(error, host).getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(error.status()),
                head ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(body));
        HttpHeaders headers = response.headers();
        headers.set(HttpHeaderNames.CONTENT_TYPE, TEXT_HTML_UTF8);
        headers.set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE);
        headers.set(JBURROW_ERROR, error.name());
        headers.set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        error.retryAfterSeconds().ifPresent(seconds -> headers.set(HttpHeaderNames.RETRY_AFTER, seconds));
        if (close) {
            headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }
        return response;
    }

    private String body(EdgeError error, String host) {
        HttpResponseStatus status = HttpResponseStatus.valueOf(error.status());
        Map<String, String> values = Map.of(
                "status", Integer.toString(status.code()),
                "reason", status.reasonPhrase(),
                "host", host,
                "explanation", error.explanation(),
                "suggestion", error.suggestion(),
                "code", error.name());
        StringBuilder page = new StringBuilder(2_048);
        for (int i = 0; i < parts.size(); i++) {
            if (i % 2 == 0) {
                page.append(parts.get(i));
            } else {
                escape(values.get(parts.get(i)), page);
            }
        }
        return page.toString();
    }

    /** Escapes markup, and everything outside printable ASCII as a character reference. */
    static void escape(String value, StringBuilder out) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append("&#").append((int) c).append(';');
                    } else {
                        out.append(c);
                    }
                }
            }
        }
    }

    private static List<String> split(String template) {
        List<String> parts = new ArrayList<>();
        int from = 0;
        while (true) {
            int open = template.indexOf(OPEN, from);
            if (open < 0) {
                parts.add(template.substring(from));
                return List.copyOf(parts);
            }
            int close = template.indexOf(CLOSE, open);
            if (close < 0) {
                throw new IllegalArgumentException("An unclosed placeholder in the error page template");
            }
            String name = template.substring(open + OPEN.length(), close);
            if (!PLACEHOLDERS.contains(name)) {
                throw new IllegalArgumentException("An unknown placeholder in the error page template: " + name);
            }
            parts.add(template.substring(from, open));
            parts.add(name);
            from = close + CLOSE.length();
        }
    }

    private static String load() {
        try (InputStream in = EdgeErrorPage.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                throw new IllegalStateException("The error page template is missing: " + TEMPLATE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the error page template", e);
        }
    }
}
