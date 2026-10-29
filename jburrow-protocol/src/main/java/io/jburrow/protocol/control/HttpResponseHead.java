package io.jburrow.protocol.control;

/**
 * {@code HEADERS}, client to server: a response head of an HTTP stream ({@code docs/protocol.md} §7.9). A {@code 1xx}
 * head has no {@code body}; the final head, 200 or more, has one.
 *
 * <p>Whether the head may be sent at this point of its stream, and the rule that a response to {@code HEAD} has an
 * empty body, need the stream's request: {@link io.jburrow.protocol.stream.HttpExchangeRules} checks them.
 */
public record HttpResponseHead(int status, HeaderFields headers, BodyFraming body) implements ControlMessage {

    private static final int SWITCHING_PROTOCOLS = 101;
    private static final int FIRST_FINAL = 200;
    private static final int NO_CONTENT = 204;
    private static final int NOT_MODIFIED = 304;

    public HttpResponseHead {
        Fields.range(status, 100, 599, "status");
        if (status == SWITCHING_PROTOCOLS) {
            throw Fields.invalid("status", "must not be 101, which is reserved");
        }
        Fields.required(headers, "headers");
        boolean hasLength = headers.contentLength().isPresent();
        if (status < FIRST_FINAL) {
            if (body != null) {
                throw Fields.invalid("body", "must be absent for a 1xx status");
            }
        } else {
            switch (Fields.required(body, "body")) {
                case EMPTY -> {}
                case LENGTH -> {
                    if (!hasLength) {
                        throw Fields.invalid("body", "must not be length without a content-length field");
                    }
                }
                case CHUNKED -> {
                    if (hasLength) {
                        throw Fields.invalid("body", "must not be chunked with a content-length field");
                    }
                }
            }
            if ((status == NO_CONTENT || status == NOT_MODIFIED) && body != BodyFraming.EMPTY) {
                throw Fields.invalid("body", "must be empty for status " + status);
            }
        }
    }

    /** Whether this is a {@code 1xx} head, which a final head follows. */
    public boolean informational() {
        return status < FIRST_FINAL;
    }
}
