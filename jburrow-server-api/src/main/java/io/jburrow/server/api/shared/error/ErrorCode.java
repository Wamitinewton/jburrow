package io.jburrow.server.api.shared.error;

/**
 * Every error the REST API reports. The constant name is the stable code clients match on, so a released constant is
 * never renamed.
 *
 * <p>The wire protocol has its own enum, {@code io.jburrow.protocol.wire.ErrorCode}, because a client never sees this
 * module. Both draw from one code space: a name in both means the same error, and {@code ErrorCodeSpaceTest} in
 * {@code jburrow-server} fails the build when the overlap changes without a decision.
 */
public enum ErrorCode {
    VALIDATION_FAILED(400),
    NOT_FOUND(404),
    INSTANCE_NOT_RECORDED(404),
    MAIL_DELIVERY_FAILED(502),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return name();
    }
}
