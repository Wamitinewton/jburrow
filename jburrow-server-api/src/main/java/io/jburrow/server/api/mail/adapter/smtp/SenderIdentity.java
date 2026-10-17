package io.jburrow.server.api.mail.adapter.smtp;

import io.jburrow.server.api.mail.domain.EmailAddress;
import java.util.Objects;

/**
 * Who every outbound message is from, fixed by configuration.
 *
 * @param sesConfigurationSet SES configuration set to tag sends with; blank or {@code null} sends no header
 */
public record SenderIdentity(EmailAddress fromAddress, String fromName, String sesConfigurationSet) {

    public SenderIdentity {
        Objects.requireNonNull(fromAddress, "fromAddress");
        Objects.requireNonNull(fromName, "fromName");
        if (fromName.isBlank()) {
            throw new IllegalArgumentException("fromName must not be blank");
        }
        requireSingleLine(fromName, "fromName");
        if (sesConfigurationSet != null) {
            requireSingleLine(sesConfigurationSet, "sesConfigurationSet");
        }
    }

    boolean hasSesConfigurationSet() {
        return sesConfigurationSet != null && !sesConfigurationSet.isBlank();
    }

    private static void requireSingleLine(String value, String name) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " must not contain CR or LF");
        }
    }
}
