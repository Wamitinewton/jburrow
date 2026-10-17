package io.jburrow.server.api.mail.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailMessageTest {

    private static final EmailAddress TO = new EmailAddress("jane@example.com");

    @Test
    void keepsItsValues() {
        EmailMessage message = new EmailMessage(TO, "Hello", "text", "<p>html</p>");

        assertThat(message.to()).isEqualTo(TO);
        assertThat(message.subject()).isEqualTo("Hello");
        assertThat(message.textBody()).isEqualTo("text");
        assertThat(message.htmlBody()).isEqualTo("<p>html</p>");
    }

    @Test
    void textFactoryLeavesHtmlOut() {
        assertThat(EmailMessage.text(TO, "Hello", "text").htmlBody()).isNull();
    }

    @Test
    void acceptsASubjectAtTheLimit() {
        String subject = "s".repeat(EmailMessage.MAX_SUBJECT_LENGTH);

        assertThat(EmailMessage.text(TO, subject, "text").subject()).hasSize(200);
    }

    @Test
    void rejectsASubjectOverTheLimit() {
        String subject = "s".repeat(EmailMessage.MAX_SUBJECT_LENGTH + 1);

        assertThatIllegalArgumentException().isThrownBy(() -> EmailMessage.text(TO, subject, "text"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsABlankSubject(String subject) {
        assertThatIllegalArgumentException().isThrownBy(() -> EmailMessage.text(TO, subject, "text"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hello\r\nBcc: x@evil.test", "Hello\nWorld", "Hello\rWorld"})
    void rejectsCarriageReturnAndLineFeedInTheSubject(String subject) {
        assertThatIllegalArgumentException().isThrownBy(() -> EmailMessage.text(TO, subject, "text"));
    }

    @Test
    void bodiesMaySpanLines() {
        assertThat(new EmailMessage(TO, "Hello", "a\r\nb", "<p>a</p>\n<p>b</p>").textBody())
                .isEqualTo("a\r\nb");
    }

    @Test
    void rejectsMissingRequiredParts() {
        assertThatNullPointerException().isThrownBy(() -> EmailMessage.text(null, "Hello", "text"));
        assertThatNullPointerException().isThrownBy(() -> EmailMessage.text(TO, null, "text"));
        assertThatNullPointerException().isThrownBy(() -> EmailMessage.text(TO, "Hello", null));
    }
}
