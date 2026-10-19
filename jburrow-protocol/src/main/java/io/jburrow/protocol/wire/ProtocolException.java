package io.jburrow.protocol.wire;

import java.util.Objects;
import java.util.Optional;

/**
 * A broken protocol rule, raised as a session error or a stream error. The message is for a human and is safe to send
 * to the peer: it never contains payload bytes or secrets.
 *
 * <p>A session error may name the tunnel it is about, which the {@code ERROR} frame carries as {@code ref}.
 *
 * <p>Untrusted input raises these on the byte path, so they carry no stack trace: filling one in per bad frame would
 * be a cheap way to make the receiver work.
 */
public final class ProtocolException extends RuntimeException {

    private final ErrorCode code;
    private final ErrorScope scope;
    private final String ref;

    private ProtocolException(ErrorCode code, ErrorScope scope, String message, String ref) {
        super(Objects.requireNonNull(message, "message"), null, false, false);
        if (message.isBlank()) {
            throw new IllegalArgumentException("A protocol error needs a message");
        }
        if (!code.scopes().contains(scope)) {
            throw new IllegalArgumentException(code + " is not a " + scope + " error");
        }
        this.code = code;
        this.scope = scope;
        this.ref = ref;
    }

    /** A session error: the receiver sends {@code ERROR} and closes the connection. */
    public static ProtocolException session(ErrorCode code, String message) {
        return new ProtocolException(Objects.requireNonNull(code, "code"), ErrorScope.SESSION, message, null);
    }

    /** A stream error: the receiver resets one stream with {@code CLOSE RST} and the session stays up. */
    public static ProtocolException stream(ErrorCode code, String message) {
        return new ProtocolException(Objects.requireNonNull(code, "code"), ErrorScope.STREAM, message, null);
    }

    /**
     * The same session error, about the tunnel {@code ref}.
     *
     * @throws IllegalStateException if this is a stream error, which has no {@code ref} on the wire
     */
    public ProtocolException forTunnel(String ref) {
        Objects.requireNonNull(ref, "ref");
        if (scope != ErrorScope.SESSION) {
            throw new IllegalStateException("Only a session error names a tunnel");
        }
        return new ProtocolException(code, scope, getMessage(), ref);
    }

    public ErrorCode code() {
        return code;
    }

    /** {@link ErrorScope#SESSION} or {@link ErrorScope#STREAM}, depending on the factory that built it. */
    public ErrorScope scope() {
        return scope;
    }

    /** The tunnel this session error is about, if any. */
    public Optional<String> ref() {
        return Optional.ofNullable(ref);
    }
}
