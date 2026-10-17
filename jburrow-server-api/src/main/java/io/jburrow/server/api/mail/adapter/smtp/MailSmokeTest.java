package io.jburrow.server.api.mail.adapter.smtp;

import io.jburrow.server.api.mail.application.port.OutboundMail;
import io.jburrow.server.api.mail.domain.EmailAddress;
import io.jburrow.server.api.mail.domain.EmailMessage;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Sends one message at startup when a recipient is configured, so an operator can prove a deployment's sender
 * identity and credentials deliver. Never throws: an optional check must not stop the process.
 */
public final class MailSmokeTest implements ApplicationRunner {

    static final String SUBJECT = "JBurrow mail check";

    private static final Logger log = LoggerFactory.getLogger(MailSmokeTest.class);

    private final OutboundMail mail;
    private final String recipient;
    private final String version;
    private final String commit;
    private final Clock clock;

    /** @param recipient where to send the check; {@code null} or blank sends nothing */
    public MailSmokeTest(OutboundMail mail, String recipient, String version, String commit, Clock clock) {
        this.mail = Objects.requireNonNull(mail, "mail");
        this.recipient = recipient;
        this.version = Objects.requireNonNull(version, "version");
        this.commit = Objects.requireNonNull(commit, "commit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (recipient == null || recipient.isBlank()) {
            return;
        }
        try {
            EmailAddress to = new EmailAddress(recipient);
            String body = "JBurrow sent this message at startup to check outbound mail.\n\n"
                    + "Version: " + version + "\n"
                    + "Commit: " + commit + "\n"
                    + "Sent at: " + clock.instant() + "\n";
            mail.send(EmailMessage.text(to, SUBJECT, body));
            log.info("mail smoke test sent to {}", to.masked());
        } catch (RuntimeException ex) {
            log.warn("mail smoke test failed: {}", ex.getClass().getName());
        }
    }
}
