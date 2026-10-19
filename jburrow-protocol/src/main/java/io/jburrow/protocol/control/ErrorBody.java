package io.jburrow.protocol.control;

import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;

/**
 * {@code ERROR}, either direction: a session error. Programs decide on {@code code}; {@code message} is for a human.
 * {@code ref} is optional, the tunnel the error is about, and may be null.
 */
public record ErrorBody(ErrorCode code, String message, String ref) implements ControlMessage {

    public ErrorBody {
        Fields.required(code, "code");
        if (!code.scopes().contains(ErrorScope.SESSION) && !code.scopes().contains(ErrorScope.UPGRADE)) {
            throw Fields.invalid("code", "must be a session or upgrade error code");
        }
        Fields.length(message, 1, Fields.MAX_ERROR_MESSAGE_LENGTH, "message");
        Fields.optionalMatching(ref, Fields.TUNNEL_REF, "ref");
    }
}
