package io.jburrow.server.api.mail.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A mailbox address, trimmed, with its domain lower-cased.
 *
 * <p>The pattern is deliberately loose: one {@code @}, a non-empty local part and a dotted domain. What it must
 * guarantee is no whitespace and no CR or LF, since the value ends up in a mail header.
 */
public record EmailAddress(String value) {

    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public EmailAddress {
        Objects.requireNonNull(value, "value");
        String trimmed = value.trim();
        if (!SHAPE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("not an email address");
        }
        int at = trimmed.indexOf('@');
        value = trimmed.substring(0, at) + "@" + trimmed.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    /** The address with all but the first character of the local part hidden, for logs. */
    public String masked() {
        int at = value.indexOf('@');
        return value.charAt(0) + "***" + value.substring(at);
    }

    @Override
    public String toString() {
        return masked();
    }
}
