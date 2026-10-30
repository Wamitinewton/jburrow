package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HeaderFields;
import io.jburrow.protocol.control.HttpResponseHead;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Locale;

/**
 * Turns a response head from the client into the response the edge writes, {@code docs/phase-3-http-tunnels/
 * ARCHITECTURE.md} §Response headers the edge changes. Only two things change: every {@code set-cookie} loses its
 * {@code Domain} attribute, so a tunnel's cookies are host-only, and the framing is the edge's, from {@code body}.
 *
 * <p>A final head becomes a {@link DefaultHttpResponse}, never a full one: its body follows as content. A {@code 1xx}
 * head is complete in itself, so it becomes a full response with no body.
 */
final class ResponseHeads {

    private static final String SET_COOKIE = "set-cookie";
    private static final String DOMAIN = "domain";
    private static final int NO_CONTENT = 204;
    private static final int NOT_MODIFIED = 304;

    private ResponseHeads() {}

    /**
     * The response for {@code head}.
     *
     * @param headRequest whether the request was {@code HEAD}: its response's {@code content-length} is the one a
     *     {@code GET} would have had, and stays as the client sent it
     * @param chunkedAllowed whether the visitor takes {@code transfer-encoding: chunked}; without it a body of unknown
     *     length is ended by closing the connection
     * @param close whether the edge closes the connection after this response
     */
    static HttpResponse build(HttpResponseHead head, boolean headRequest, boolean chunkedAllowed, boolean close) {
        HttpResponseStatus status = HttpResponseStatus.valueOf(head.status());
        if (head.informational()) {
            HttpResponse informational =
                    new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
            copy(head.headers(), informational.headers());
            return informational;
        }
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        HttpHeaders headers = response.headers();
        copy(head.headers(), headers);
        switch (head.body()) {
            case EMPTY -> {
                if (head.status() == NO_CONTENT) {
                    headers.remove(HttpHeaderNames.CONTENT_LENGTH);
                } else if (!headRequest && head.status() != NOT_MODIFIED) {
                    headers.set(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);
                }
            }
            case LENGTH -> {}
            case CHUNKED -> {
                if (chunkedAllowed) {
                    headers.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
                }
            }
        }
        if (close) {
            headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }
        return response;
    }

    /** Whether the response's body is ended by closing the connection, since the visitor can't take chunks. */
    static boolean closeDelimited(HttpResponseHead head, boolean chunkedAllowed) {
        return head.body() == BodyFraming.CHUNKED && !chunkedAllowed;
    }

    private static void copy(HeaderFields fields, HttpHeaders headers) {
        for (HeaderFields.Field field : fields.fields()) {
            String value = field.name().equals(SET_COOKIE) ? withoutDomain(field.value()) : field.value();
            headers.add(field.name(), value);
        }
    }

    /**
     * {@code setCookie} without its {@code Domain} attributes, every other byte as it was. The first part is the
     * cookie's own name and value, never an attribute, even when the cookie is called {@code domain}.
     */
    static String withoutDomain(String setCookie) {
        String[] parts = setCookie.split(";", -1);
        StringBuilder kept = new StringBuilder(setCookie.length()).append(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!isDomain(parts[i])) {
                kept.append(';').append(parts[i]);
            }
        }
        return kept.toString();
    }

    private static boolean isDomain(String attribute) {
        int equals = attribute.indexOf('=');
        String name = equals < 0 ? attribute : attribute.substring(0, equals);
        return name.strip().toLowerCase(Locale.ROOT).equals(DOMAIN);
    }
}
