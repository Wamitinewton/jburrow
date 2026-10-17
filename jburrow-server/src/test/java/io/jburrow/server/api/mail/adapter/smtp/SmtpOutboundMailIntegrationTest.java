package io.jburrow.server.api.mail.adapter.smtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.jburrow.server.api.mail.application.MailDeliveryException;
import io.jburrow.server.api.mail.domain.EmailAddress;
import io.jburrow.server.api.mail.domain.EmailMessage;
import io.jburrow.server.api.shared.error.ErrorCode;
import io.jburrow.server.support.MailpitContainer;
import io.jburrow.server.support.TestContainersConfiguration;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Import(TestContainersConfiguration.class)
class SmtpOutboundMailIntegrationTest {

    private static final EmailAddress FROM = new EmailAddress("no-reply@jburrow.test");
    private static final EmailAddress TO = new EmailAddress("jane@example.com");

    @Autowired
    private JavaMailSender javaMailSender;

    @Autowired
    private MailpitContainer mailpit;

    @Test
    void textOnlyMessageArrivesFromTheConfiguredSender() {
        String subject = uniqueSubject();

        mail(null).send(EmailMessage.text(TO, subject, "plain body"));

        JsonNode message = delivered(subject);
        assertThat(message.at("/From/Name").asString()).isEqualTo("JBurrow Test");
        assertThat(message.at("/From/Address").asString()).isEqualTo(FROM.value());
        assertThat(message.at("/To/0/Address").asString()).isEqualTo(TO.value());
        assertThat(message.get("Text").asString().strip()).isEqualTo("plain body");
        assertThat(message.get("HTML").asString()).isEmpty();
    }

    @Test
    void textAndHtmlMessageCarriesBothBodies() {
        String subject = uniqueSubject();

        mail(null).send(new EmailMessage(TO, subject, "plain body", "<p>html body</p>"));

        JsonNode message = delivered(subject);
        assertThat(message.at("/From/Name").asString()).isEqualTo("JBurrow Test");
        assertThat(message.get("Text").asString().strip()).isEqualTo("plain body");
        assertThat(message.get("HTML").asString()).contains("<p>html body</p>");
    }

    @Test
    void configurationSetIsSentAsAHeader() {
        String subject = uniqueSubject();

        mail("jburrow-test").send(EmailMessage.text(TO, subject, "body"));

        String id = delivered(subject).get("ID").asString();
        assertThat(mailpit.header(id, SmtpOutboundMail.SES_CONFIGURATION_SET_HEADER))
                .containsExactly("jburrow-test");
    }

    @Test
    void noConfigurationSetSendsNoHeader() {
        String subject = uniqueSubject();

        mail(" ").send(EmailMessage.text(TO, subject, "body"));

        String id = delivered(subject).get("ID").asString();
        assertThat(mailpit.header(id, SmtpOutboundMail.SES_CONFIGURATION_SET_HEADER))
                .isEmpty();
    }

    @Test
    void unreachableServerThrowsWithoutEchoingAddresses() throws IOException {
        JavaMailSenderImpl closed = new JavaMailSenderImpl();
        closed.setHost("localhost");
        closed.setPort(closedPort());
        SmtpOutboundMail mail = new SmtpOutboundMail(closed, new SenderIdentity(FROM, "JBurrow Test", null));

        assertThatExceptionOfType(MailDeliveryException.class)
                .isThrownBy(() -> mail.send(EmailMessage.text(TO, uniqueSubject(), "body")))
                .satisfies(ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.MAIL_DELIVERY_FAILED);
                    assertThat(ex.getMessage()).doesNotContain("@", "jane", "no-reply");
                    assertThat(ex.getCause()).isNotNull();
                });
    }

    private SmtpOutboundMail mail(String sesConfigurationSet) {
        return new SmtpOutboundMail(javaMailSender, new SenderIdentity(FROM, "JBurrow Test", sesConfigurationSet));
    }

    private JsonNode delivered(String subject) {
        return mailpit.findBySubject(subject)
                .orElseThrow(() -> new AssertionError("no message with subject " + subject));
    }

    private static String uniqueSubject() {
        return "test " + UUID.randomUUID();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
