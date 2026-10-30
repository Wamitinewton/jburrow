package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.jburrow.protocol.wire.ErrorCode;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Why the edge answered a request itself, with an error page: the codes of
 * {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Error pages. The constant name is the stable code a page shows
 * and its {@code jburrow-error} header carries.
 *
 * <p>No peer ever receives one of these, so they are the server's, not the protocol's. Three of them render a
 * protocol code of the same name and say so in {@link #protocolCode()}; {@code ErrorCodeSpaceTest} allows a shared
 * name only then.
 */
public enum EdgeError {
    TUNNEL_NOT_FOUND(
            404,
            "No tunnel is serving this address.",
            "If this is your tunnel, check that jburrow is running and connected.",
            OptionalInt.empty(),
            null),
    BAD_REQUEST(
            400,
            "This request can't be forwarded through a tunnel.",
            "If this is your tunnel, the program sending the request needs to send valid HTTP/1.1.",
            OptionalInt.empty(),
            null),
    REQUEST_TOO_LARGE(
            413,
            "The request body is larger than this server forwards.",
            "If this is your tunnel, send a smaller body or ask the server's operator about the limit.",
            OptionalInt.empty(),
            null),
    REQUEST_HEADERS_TOO_LARGE(
            431,
            "The request's headers are too large to forward.",
            "If this is your tunnel, send fewer or smaller headers, such as cookies.",
            OptionalInt.empty(),
            null),
    UPGRADE_NOT_SUPPORTED(
            501,
            "Tunnels don't carry WebSocket or other upgraded connections yet.",
            "If this is your tunnel, use plain HTTP requests for now.",
            OptionalInt.empty(),
            null),
    LOCAL_SERVICE_UNREACHABLE(
            502,
            "The tunnel is up, but its local service didn't accept a connection.",
            "If this is your tunnel, check that your service is running on the port jburrow forwards to.",
            OptionalInt.empty(),
            ErrorCode.LOCAL_SERVICE_UNREACHABLE),
    LOCAL_SERVICE_ERROR(
            502,
            "The tunnel's local service sent a response that can't be forwarded.",
            "If this is your tunnel, check your service's logs for this request.",
            OptionalInt.empty(),
            ErrorCode.LOCAL_SERVICE_ERROR),
    TUNNEL_BUSY(
            503,
            "The tunnel is handling as many requests as it can.",
            "Try again in a moment. If this is your tunnel, it has reached its limit of requests at once.",
            OptionalInt.of(1),
            null),
    /** {@code Retry-After} as for a refused upgrade, {@code docs/protocol.md} §3.5. */
    SERVER_SHUTTING_DOWN(
            503,
            "The tunnel server is restarting.",
            "Try again in a few seconds. If this is your tunnel, start jburrow again once the server is back.",
            OptionalInt.of(5),
            ErrorCode.SERVER_SHUTTING_DOWN),
    RESPONSE_TIMEOUT(
            504,
            "The tunnel's local service didn't start a response in time.",
            "If this is your tunnel, check whether your service is stuck on this request.",
            OptionalInt.empty(),
            null);

    private final int status;
    private final String explanation;
    private final String suggestion;
    private final OptionalInt retryAfterSeconds;
    private final ErrorCode protocolCode;

    EdgeError(
            int status, String explanation, String suggestion, OptionalInt retryAfterSeconds, ErrorCode protocolCode) {
        this.status = status;
        this.explanation = Objects.requireNonNull(explanation, "explanation");
        this.suggestion = Objects.requireNonNull(suggestion, "suggestion");
        this.retryAfterSeconds = retryAfterSeconds;
        this.protocolCode = protocolCode;
    }

    /** The HTTP status of the page. */
    public int status() {
        return status;
    }

    /** One sentence, for the visitor, saying what happened. */
    public String explanation() {
        return explanation;
    }

    /** One sentence saying what the tunnel's owner, or the visitor, can do about it. */
    public String suggestion() {
        return suggestion;
    }

    /** The {@code Retry-After} the page carries, if any. */
    public OptionalInt retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** The protocol code this page reports to the visitor, when it renders one under the same name. */
    public Optional<ErrorCode> protocolCode() {
        return Optional.ofNullable(protocolCode);
    }
}
