package io.jburrow.protocol.control;

import java.util.OptionalLong;

/**
 * The request head of an HTTP stream, in its {@code OPEN} ({@code docs/protocol.md} §7.7). {@code target} is exactly
 * what the edge received, never decoded or normalised.
 */
public record HttpRequestHead(String method, String target, HeaderFields headers, BodyFraming body) {

    private static final String CONNECT = "CONNECT";
    private static final String OPTIONS = "OPTIONS";
    private static final String ASTERISK_FORM = "*";

    public HttpRequestHead {
        Fields.matching(method, Fields.METHOD, "method");
        if (method.equals(CONNECT)) {
            throw Fields.invalid("method", "must not be CONNECT");
        }
        Fields.required(target, "target");
        if (!Fields.ORIGIN_FORM.matcher(target).matches()
                && !(method.equals(OPTIONS) && target.equals(ASTERISK_FORM))) {
            throw Fields.invalid(
                    "target", "must be 1 to 4096 visible ASCII characters starting with /, or * for OPTIONS");
        }
        Fields.required(headers, "headers");
        if (headers.all("host").size() != 1) {
            throw Fields.invalid("headers", "must have exactly one host field");
        }
        OptionalLong contentLength = headers.contentLength();
        switch (Fields.required(body, "body")) {
            case EMPTY -> {
                if (contentLength.orElse(0) != 0) {
                    throw Fields.invalid("body", "must not be empty with a content-length other than 0");
                }
            }
            case LENGTH -> {
                if (contentLength.isEmpty()) {
                    throw Fields.invalid("body", "must not be length without a content-length field");
                }
            }
            case CHUNKED -> {
                if (contentLength.isPresent()) {
                    throw Fields.invalid("body", "must not be chunked with a content-length field");
                }
            }
        }
    }
}
