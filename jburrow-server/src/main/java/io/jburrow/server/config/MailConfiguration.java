package io.jburrow.server.config;

import io.jburrow.server.api.instance.application.port.BuildIdentitySource;
import io.jburrow.server.api.instance.domain.BuildIdentity;
import io.jburrow.server.api.mail.adapter.smtp.MailSmokeTest;
import io.jburrow.server.api.mail.adapter.smtp.SenderIdentity;
import io.jburrow.server.api.mail.adapter.smtp.SmtpOutboundMail;
import io.jburrow.server.api.mail.application.port.OutboundMail;
import io.jburrow.server.api.mail.domain.EmailAddress;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wires the {@code OutboundMail} port to the SMTP adapter.
 *
 * <p>{@link SmtpOutboundMail} is imported rather than built in a {@code @Bean} method so that Spring hands it Boot's
 * {@code JavaMailSender}: naming that type here would put Spring Mail in the composition root, which the layer rules
 * forbid.
 */
@Configuration(proxyBeanMethods = false)
@Import(SmtpOutboundMail.class)
class MailConfiguration {

    @Bean
    SenderIdentity senderIdentity(JBurrowProperties properties) {
        JBurrowProperties.Mail mail = properties.mail();
        return new SenderIdentity(new EmailAddress(mail.fromAddress()), mail.fromName(), mail.sesConfigurationSet());
    }

    /** Version and commit are resolved here because the mail feature may only see the instance feature's ports. */
    @Bean
    MailSmokeTest mailSmokeTest(
            OutboundMail outboundMail, JBurrowProperties properties, BuildIdentitySource identity, Clock clock) {
        BuildIdentity build = identity.current();
        return new MailSmokeTest(
                outboundMail, properties.mail().smokeTestRecipient(), build.version(), build.commit(), clock);
    }
}
