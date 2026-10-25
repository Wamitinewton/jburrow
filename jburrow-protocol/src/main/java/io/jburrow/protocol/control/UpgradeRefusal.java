package io.jburrow.protocol.control;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * The JSON body of a refused upgrade, {@code docs/protocol.md} §3.5. Not a control payload: it travels in an HTTP
 * response before any frame, and §7's rules don't apply to it. A client decides on {@code code} alone.
 *
 * @param success always {@code false}
 * @param supported every subprotocol token the server speaks; only with
 *     {@link ErrorCode#UNSUPPORTED_PROTOCOL_VERSION}, otherwise null
 * @param timestamp the time of the refusal, an ISO-8601 instant in UTC
 * @param path the request path
 */
public record UpgradeRefusal(
        boolean success, ErrorCode code, String message, List<String> supported, String timestamp, String path) {

    public UpgradeRefusal {
        if (success) {
            throw Fields.invalid("success", "must be false");
        }
        Fields.required(code, "code");
        if (!code.scopes().contains(ErrorScope.UPGRADE)) {
            throw Fields.invalid("code", "must be an upgrade error code");
        }
        Fields.length(message, 1, Fields.MAX_ERROR_MESSAGE_LENGTH, "message");
        if (supported != null) {
            supported = Fields.list(supported, 1, Fields.MAX_CAPABILITIES, "supported");
        }
        Fields.required(timestamp, "timestamp");
        Fields.required(path, "path");
    }

    /** A refusal with {@code code}, which must not be {@link ErrorCode#UNSUPPORTED_PROTOCOL_VERSION}. */
    public static UpgradeRefusal of(ErrorCode code, String message, Instant at, String path) {
        if (code == ErrorCode.UNSUPPORTED_PROTOCOL_VERSION) {
            throw new IllegalArgumentException("An unsupported version refusal lists the supported versions");
        }
        return new UpgradeRefusal(false, code, message, null, format(at), path);
    }

    /** An {@link ErrorCode#UNSUPPORTED_PROTOCOL_VERSION} refusal listing what the server speaks. */
    public static UpgradeRefusal unsupportedVersion(String message, List<String> supported, Instant at, String path) {
        return new UpgradeRefusal(false, ErrorCode.UNSUPPORTED_PROTOCOL_VERSION, message, supported, format(at), path);
    }

    private static String format(Instant at) {
        return DateTimeFormatter.ISO_INSTANT.format(Objects.requireNonNull(at, "at"));
    }
}
