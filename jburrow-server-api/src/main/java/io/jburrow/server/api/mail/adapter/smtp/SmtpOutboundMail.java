package io.jburrow.server.api.mail.adapter.smtp;

import io.jburrow.server.api.mail.application.MailDeliveryException;
import io.jburrow.server.api.mail.application.port.OutboundMail;
import io.jburrow.server.api.mail.domain.EmailMessage;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Sends through any SMTP server: Mailpit locally, Amazon SES's SMTP interface in production. */
public final class SmtpOutboundMail implements OutboundMail {

    static final String SES_CONFIGURATION_SET_HEADER = "X-SES-CONFIGURATION-SET";

    private static final Logger log = LoggerFactory.getLogger(SmtpOutboundMail.class);

    private final JavaMailSender sender;
    private final SenderIdentity identity;

    public SmtpOutboundMail(JavaMailSender sender, SenderIdentity identity) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public void send(EmailMessage message) {
        Objects.requireNonNull(message, "message");
        try {
            sender.send(toMime(message));
        } catch (MailException | MessagingException | UnsupportedEncodingException ex) {
            log.warn(
                    "mail delivery failed: to={} subject={} error={}",
                    message.to().masked(),
                    message.subject(),
                    ex.getClass().getName());
            throw new MailDeliveryException(ex);
        }
    }

    private MimeMessage toMime(EmailMessage message) throws MessagingException, UnsupportedEncodingException {
        MimeMessage mime = sender.createMimeMessage();
        boolean html = message.htmlBody() != null;
        MimeMessageHelper helper = new MimeMessageHelper(mime, html, StandardCharsets.UTF_8.name());
        helper.setFrom(identity.fromAddress().value(), identity.fromName());
        helper.setTo(message.to().value());
        helper.setSubject(message.subject());
        if (html) {
            helper.setText(message.textBody(), message.htmlBody());
        } else {
            helper.setText(message.textBody());
        }
        if (identity.hasSesConfigurationSet()) {
            mime.setHeader(SES_CONFIGURATION_SET_HEADER, identity.sesConfigurationSet());
        }
        return mime;
    }
}
