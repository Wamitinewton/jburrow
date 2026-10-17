package io.jburrow.server.api.shared.error;

import java.util.Objects;

/** Base of every domain error. Its message reaches the client, so it never carries secrets or internals. */
public abstract class JBurrowException extends RuntimeException {

    private final ErrorCode errorCode;

    protected JBurrowException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    protected JBurrowException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
