package io.jburrow.client;

import io.jburrow.protocol.wire.ErrorCode;
import java.io.Serial;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A session that couldn't be established. Programs decide on {@link #kind()} and {@link #code()}; the message is for a
 * person, and for a server's refusal it is the server's own, with control characters removed.
 */
public final class TunnelStartException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** No connection: the name didn't resolve, nothing accepted it, or it closed before the session was set up. */
        CONNECT_FAILED,
        /** TLS failed: the certificate isn't trusted or isn't for the server's name, or the handshake failed. */
        TLS_FAILED,
        /** The server, or something in front of it, answered the upgrade with an HTTP error instead of 101. */
        UPGRADE_REFUSED,
        /** The server sent {@code ERROR}, such as {@code AUTHTOKEN_INVALID} or {@code PORT_UNAVAILABLE}. */
        SERVER_ERROR,
        /**
         * The server doesn't support what the tunnels need, such as HTTP tunnels: its {@code HELLO} didn't list the
         * capability. This client ended the session without registering.
         */
        UNSUPPORTED_BY_SERVER,
        /** The upgrade or the handshake didn't complete in time. */
        TIMEOUT,
        /** The server broke the protocol, or this client failed, and this client ended the session with the code. */
        LOCAL_ERROR
    }

    private final Kind kind;
    private final ErrorCode code;
    private final List<String> supportedVersions;
    private final int status;
    private final Duration retryAfter;

    public TunnelStartException(Kind kind, String message) {
        this(kind, message, Optional.empty(), List.of(), null);
    }

    /**
     * @param supportedVersions the subprotocol tokens the server speaks, from an {@code UNSUPPORTED_PROTOCOL_VERSION}
     *     refusal; empty otherwise
     */
    public TunnelStartException(
            Kind kind, String message, Optional<ErrorCode> code, List<String> supportedVersions, Throwable cause) {
        this(kind, message, code, supportedVersions, OptionalInt.empty(), Optional.empty(), cause);
    }

    /**
     * @param supportedVersions the subprotocol tokens the server speaks, from an {@code UNSUPPORTED_PROTOCOL_VERSION}
     *     refusal; empty otherwise
     * @param status the HTTP status of a refused upgrade
     * @param retryAfter a refused upgrade's {@code Retry-After}
     */
    public TunnelStartException(
            Kind kind,
            String message,
            Optional<ErrorCode> code,
            List<String> supportedVersions,
            OptionalInt status,
            Optional<Duration> retryAfter,
            Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.code = Objects.requireNonNull(code, "code").orElse(null);
        this.supportedVersions = List.copyOf(supportedVersions);
        this.status = Objects.requireNonNull(status, "status").orElse(-1);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter").orElse(null);
    }

    public Kind kind() {
        return kind;
    }

    /** The code from the server's refusal or {@code ERROR}, or the one this client sent; empty when there was none. */
    public Optional<ErrorCode> code() {
        return Optional.ofNullable(code);
    }

    /** Non-empty only for {@code UNSUPPORTED_PROTOCOL_VERSION}: every token the server speaks. */
    public List<String> supportedVersions() {
        return supportedVersions;
    }

    /** For {@link Kind#UPGRADE_REFUSED}: the HTTP status the upgrade was refused with. */
    public OptionalInt status() {
        return status < 0 ? OptionalInt.empty() : OptionalInt.of(status);
    }

    /** For {@link Kind#UPGRADE_REFUSED}: how long the refusal's {@code Retry-After} asked the client to wait. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
