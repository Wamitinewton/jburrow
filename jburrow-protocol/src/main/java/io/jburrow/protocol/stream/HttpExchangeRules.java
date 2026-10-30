package io.jburrow.protocol.stream;

import io.jburrow.protocol.control.BodyFraming;
import io.jburrow.protocol.control.HttpRequestHead;
import io.jburrow.protocol.control.HttpResponseHead;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolException;
import java.util.Objects;

/**
 * The rules of one HTTP stream that its heads alone can't check ({@code docs/protocol.md} §9.7): one final response
 * head, after any number of {@code 1xx} heads and before any response body; a body that matches its head's framing,
 * neither longer nor shorter than its {@code content-length}; and an empty response to {@code HEAD}.
 *
 * <p>Each side holds one per HTTP stream next to the stream's {@link StreamLifecycle}, and feeds it every frame of the
 * stream after the lifecycle has accepted it, in both directions. The request is the server's and the response the
 * client's, so a broken rule is either the peer's or a bug in this side: at the server ({@link #HttpExchangeRules}), a
 * broken request throws {@link IllegalStateException} and a broken response a {@link ProtocolException}, a session
 * error; at the client ({@link #atClient}), the other way round.
 *
 * <p>Not thread-safe. Confined to the thread that owns its session.
 */
public final class HttpExchangeRules {

    private static final String HEAD = "HEAD";

    private final HttpRequestHead request;
    private final Body requestBody;
    private final boolean atClient;
    private Body responseBody;

    /** The rules as the server holds them: it sends the request and receives the response. */
    public HttpExchangeRules(HttpRequestHead request) {
        this(request, false);
    }

    private HttpExchangeRules(HttpRequestHead request, boolean atClient) {
        this.request = Objects.requireNonNull(request, "request");
        this.requestBody =
                new Body(request.body(), request.headers().contentLength().orElse(0));
        this.atClient = atClient;
    }

    /** The rules as the client holds them: it receives the request and sends the response. */
    public static HttpExchangeRules atClient(HttpRequestHead request) {
        return new HttpExchangeRules(request, true);
    }

    public HttpRequestHead request() {
        return request;
    }

    /** Whether the final response head has arrived. */
    public boolean responseStarted() {
        return responseBody != null;
    }

    /**
     * {@code DATA} of the request body, {@code bytes} long.
     *
     * @throws ProtocolException at the client: {@code PROTOCOL_ERROR} on an empty body, past the body's
     *     {@code content-length}, or after its end
     */
    public void onRequestData(int bytes) {
        String broken = requestBody.take(requireLength(bytes));
        if (broken != null) {
            throw requestBroken("the request body " + broken);
        }
    }

    /**
     * The server's {@code CLOSE}: the request body is complete.
     *
     * @throws ProtocolException at the client: {@code PROTOCOL_ERROR} short of the body's {@code content-length}
     */
    public void onRequestEnd() {
        String broken = requestBody.end();
        if (broken != null) {
            throw requestBroken("the request body " + broken);
        }
    }

    /**
     * A response head from the client.
     *
     * @throws ProtocolException at the server: {@code PROTOCOL_ERROR} for a head after the final one,
     *     {@code VALIDATION_FAILED} for a final head whose body isn't empty in answer to {@code HEAD}
     */
    public void onResponseHead(HttpResponseHead head) {
        Objects.requireNonNull(head, "head");
        if (responseBody != null) {
            throw responseBroken(
                    ErrorCode.PROTOCOL_ERROR,
                    head.informational()
                            ? "HEADERS with a 1xx status after the final response head"
                            : "a second final HEADERS on the stream");
        }
        if (head.informational()) {
            return;
        }
        if (request.method().equals(HEAD) && head.body() != BodyFraming.EMPTY) {
            throw responseBroken(ErrorCode.VALIDATION_FAILED, "body must be empty in a response to a HEAD request");
        }
        responseBody = new Body(head.body(), head.headers().contentLength().orElse(0));
    }

    /**
     * {@code DATA} of the response body from the client, {@code bytes} long.
     *
     * @throws ProtocolException at the server: {@code PROTOCOL_ERROR} before the final head, on an empty body, or past
     *     the body's {@code content-length}
     */
    public void onResponseData(int bytes) {
        requireLength(bytes);
        if (responseBody == null) {
            throw responseBroken(ErrorCode.PROTOCOL_ERROR, "DATA before the final response head");
        }
        String broken = responseBody.take(bytes);
        if (broken != null) {
            throw responseBroken(ErrorCode.PROTOCOL_ERROR, "the response body " + broken);
        }
    }

    /**
     * The client's {@code CLOSE}: the response body is complete.
     *
     * @throws ProtocolException at the server: {@code PROTOCOL_ERROR} before the final head, or short of the body's
     *     {@code content-length}
     */
    public void onResponseEnd() {
        if (responseBody == null) {
            throw responseBroken(ErrorCode.PROTOCOL_ERROR, "CLOSE before the final response head");
        }
        String broken = responseBody.end();
        if (broken != null) {
            throw responseBroken(ErrorCode.PROTOCOL_ERROR, "the response body " + broken);
        }
    }

    private static int requireLength(int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("DATA carries at least one byte: " + bytes);
        }
        return bytes;
    }

    /** The request is the server's: the client's peer broke it, or the server has a bug. */
    private RuntimeException requestBroken(String message) {
        return atClient
                ? ProtocolException.session(ErrorCode.PROTOCOL_ERROR, capitalized(message))
                : new IllegalStateException(capitalized(message));
    }

    /** The response is the client's: the server's peer broke it, or the client has a bug. */
    private RuntimeException responseBroken(ErrorCode code, String message) {
        return atClient ? new IllegalStateException(capitalized(message)) : ProtocolException.session(code, message);
    }

    private static String capitalized(String message) {
        return Character.toUpperCase(message.charAt(0)) + message.substring(1);
    }

    /** One direction's body: its framing, what is left of a known length, and whether it has ended. */
    private static final class Body {

        private final BodyFraming framing;
        private long remaining;
        private boolean ended;

        Body(BodyFraming framing, long length) {
            this.framing = framing;
            this.remaining = framing == BodyFraming.LENGTH ? length : 0;
        }

        /** Counts {@code bytes} of the body. Returns the rule they break, or {@code null}. */
        String take(int bytes) {
            if (ended) {
                return "has already ended";
            }
            return switch (framing) {
                case EMPTY -> "is empty";
                case LENGTH -> {
                    if (bytes > remaining) {
                        yield "is longer than its content-length";
                    }
                    remaining -= bytes;
                    yield null;
                }
                case CHUNKED -> null;
            };
        }

        /** Ends the body. Returns the rule that breaks, or {@code null}. */
        String end() {
            if (ended) {
                return "has already ended";
            }
            ended = true;
            return remaining > 0 ? "ended short of its content-length" : null;
        }
    }
}
