package io.jburrow.server.api.mail.application.port;

import io.jburrow.server.api.mail.application.MailDeliveryException;
import io.jburrow.server.api.mail.domain.EmailMessage;

/** Sends mail from the configured sender. Retrying a failed send is the caller's decision. */
public interface OutboundMail {

    /** @throws MailDeliveryException if the mail server did not accept the message */
    void send(EmailMessage message);
}
