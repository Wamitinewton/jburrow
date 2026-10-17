package io.jburrow.server.api.mail.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressTest {

    @Test
    void trimsAndLowerCasesOnlyTheDomain() {
        assertThat(new EmailAddress("  Jane.Doe@Example.COM ").value()).isEqualTo("Jane.Doe@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@b.co", "first.last+tag@sub.example.org", "x@localhost.localdomain"})
    void acceptsOrdinaryAddresses(String address) {
        assertThat(new EmailAddress(address).value()).isEqualTo(address);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "   ",
                "no-at-sign",
                "@example.com",
                "jane@",
                "jane@localhost",
                "jane@@example.com",
                "ja@ne@example.com",
                "ja ne@example.com",
                "jane@exa mple.com",
                "jane\t@example.com"
            })
    void rejectsMalformedAddresses(String address) {
        assertThatIllegalArgumentException().isThrownBy(() -> new EmailAddress(address));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jane@example.com\r\nBcc: x@evil.test", "jane\n@example.com", "jane@example.com\rx"})
    void rejectsCarriageReturnAndLineFeed(String address) {
        assertThatIllegalArgumentException().isThrownBy(() -> new EmailAddress(address));
    }

    @Test
    void rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new EmailAddress(null));
    }

    @Test
    void masksAllButTheFirstCharacterOfTheLocalPart() {
        assertThat(new EmailAddress("jane@example.com").masked()).isEqualTo("j***@example.com");
        assertThat(new EmailAddress("j@example.com").masked()).isEqualTo("j***@example.com");
    }

    @Test
    void toStringIsMasked() {
        assertThat(new EmailAddress("jane@example.com")).hasToString("j***@example.com");
    }

    @Test
    void errorMessageDoesNotEchoTheInput() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EmailAddress("secret-person@nowhere"))
                .withMessageNotContaining("secret-person");
    }
}
