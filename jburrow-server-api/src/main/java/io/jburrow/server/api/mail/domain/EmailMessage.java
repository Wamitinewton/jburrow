package io.jburrow.server.api.mail.domain;

import java.util.Objects;

/**
 * One outbound message. The sender is not part of it: every message goes out from the configured sender identity.
 *
 * @param htmlBody optional HTML alternative to {@code textBody}; {@code null} sends plain text only
 */
public record EmailMessage(EmailAddress to, String subject, String textBody, String htmlBody) {

    public static final int MAX_SUBJECT_LENGTH = 200;

    public EmailMessage {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(textBody, "textBody");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (subject.indexOf('\r') >= 0 || subject.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("subject must not contain CR or LF");
        }
        if (subject.length() > MAX_SUBJECT_LENGTH) {
            throw new IllegalArgumentException("subject must be at most " + MAX_SUBJECT_LENGTH + " characters");
        }
    }

    public static EmailMessage text(EmailAddress to, String subject, String textBody) {
        return new EmailMessage(to, subject, textBody, null);
    }
}
