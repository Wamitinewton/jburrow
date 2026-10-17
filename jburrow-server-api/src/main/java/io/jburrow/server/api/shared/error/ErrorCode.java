package io.jburrow.server.api.shared.error;

/**
 * Every error JBurrow reports, over REST and in protocol {@code ERROR} frames. The constant name is the stable code
 * clients match on, so a released constant is never renamed.
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
