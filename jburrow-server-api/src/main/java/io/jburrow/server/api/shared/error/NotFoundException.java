package io.jburrow.server.api.shared.error;

/** A requested thing does not exist. Subclasses name a more specific {@code 404} code. */
public class NotFoundException extends JBurrowException {

    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }

    protected NotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
