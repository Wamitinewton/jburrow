package io.jburrow.server.api.mail.adapter.smtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.jburrow.server.api.mail.application.MailDeliveryException;
import io.jburrow.server.api.mail.application.port.OutboundMail;
import io.jburrow.server.api.mail.domain.EmailMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;

class MailSmokeTestTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T08:00:00Z"), ZoneOffset.UTC);

    private final RecordingOutboundMail mail = new RecordingOutboundMail();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void blankRecipientSendsNothing(String recipient) {
        run(new MailSmokeTest(mail, recipient, "0.1.0", "abc1234", CLOCK));

        assertThat(mail.sent).isEmpty();
    }

    @Test
    void setRecipientSendsOnceWithBuildAndTime() {
        run(new MailSmokeTest(mail, "ops@example.com", "0.1.0", "abc1234", CLOCK));

        assertThat(mail.sent).singleElement().satisfies(message -> {
            assertThat(message.to().value()).isEqualTo("ops@example.com");
            assertThat(message.subject()).isEqualTo("JBurrow mail check");
            assertThat(message.textBody()).contains("0.1.0", "abc1234", "2026-10-17T08:00:00Z");
            assertThat(message.htmlBody()).isNull();
        });
    }

    @Test
    void aFailingSendDoesNotPropagate() {
        OutboundMail failing = message -> {
            throw new MailDeliveryException(new IllegalStateException("smtp down"));
        };

        assertThatNoException()
                .isThrownBy(() -> run(new MailSmokeTest(failing, "ops@example.com", "0.1.0", "abc1234", CLOCK)));
    }

    @Test
    void anUnusableRecipientDoesNotPropagate() {
        assertThatNoException()
                .isThrownBy(() -> run(new MailSmokeTest(mail, "not-an-address", "0.1.0", "abc1234", CLOCK)));
        assertThat(mail.sent).isEmpty();
    }

    private static void run(MailSmokeTest smokeTest) {
        smokeTest.run(new DefaultApplicationArguments());
    }

    private static final class RecordingOutboundMail implements OutboundMail {

        private final List<EmailMessage> sent = new ArrayList<>();

        @Override
        public void send(EmailMessage message) {
            sent.add(message);
        }
    }
}
