package io.jburrow.client.tunnel.domain;

import io.jburrow.protocol.wire.ErrorCode;
import java.util.Objects;
import java.util.Optional;

/**
 * Why an established session ended. Programs decide on {@link #reason()} and {@link #code()}; {@link #message()} is for
 * a person.
 *
 * @param code the protocol error code, when the session ended with one: the server's {@code ERROR}, or the error this
 *     client sent
 */
public record TunnelClosed(Reason reason, Optional<ErrorCode> code, String message) {

    public enum Reason {
        /** The user closed the tunnel: {@code TunnelHandle.close()}. */
        USER_CLOSED,
        /** The server sent {@code ERROR}, such as {@code SERVER_SHUTTING_DOWN}. */
        SERVER_ERROR,
        /** One side heard nothing for the idle timeout: this client, or the server that said so in its {@code ERROR}. */
        HEARTBEAT_TIMEOUT,
        /** The connection failed or closed without an {@code ERROR}. Never on the wire. */
        CONNECTION_LOST,
        /** This client found the server breaking the protocol, or failed itself, and sent {@code ERROR}. */
        LOCAL_ERROR
    }

    public TunnelClosed {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    public static TunnelClosed userClosed() {
        return new TunnelClosed(Reason.USER_CLOSED, Optional.empty(), "The tunnel was closed.");
    }

    public static TunnelClosed connectionLost(String message) {
        return new TunnelClosed(Reason.CONNECTION_LOST, Optional.empty(), message);
    }

    /** The server's {@code ERROR}; its {@code HEARTBEAT_TIMEOUT} counts as a heartbeat timeout. */
    public static TunnelClosed serverError(ErrorCode code, String message) {
        return new TunnelClosed(heartbeatOr(code, Reason.SERVER_ERROR), Optional.of(code), message);
    }

    /** The {@code ERROR} this client sent; {@code HEARTBEAT_TIMEOUT} counts as a heartbeat timeout. */
    public static TunnelClosed localError(ErrorCode code, String message) {
        return new TunnelClosed(heartbeatOr(code, Reason.LOCAL_ERROR), Optional.of(code), message);
    }

    private static Reason heartbeatOr(ErrorCode code, Reason otherwise) {
        return Objects.requireNonNull(code, "code") == ErrorCode.HEARTBEAT_TIMEOUT
                ? Reason.HEARTBEAT_TIMEOUT
                : otherwise;
    }
}
