package io.jburrow.server.api.mail.application;

import io.jburrow.server.api.shared.error.ErrorCode;
import io.jburrow.server.api.shared.error.JBurrowException;

/**
 * The mail server did not accept a message. The message is fixed text because an SMTP reply can echo addresses; the
 * reply stays in the cause, for logs.
 */
public final class MailDeliveryException extends JBurrowException {

    public MailDeliveryException(Throwable cause) {
        super(ErrorCode.MAIL_DELIVERY_FAILED, "The message could not be delivered to the mail server.", cause);
    }
}
