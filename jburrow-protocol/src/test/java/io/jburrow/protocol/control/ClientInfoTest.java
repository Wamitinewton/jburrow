package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientInfoTest {

    private static final String NAME_RULE = "name must match [A-Za-z0-9._-]{1,64}";
    private static final String VERSION_RULE = "version must match [A-Za-z0-9.+_-]{1,64}";

    @Test
    void acceptsTheArchitectureExample() {
        ClientInfo info = new ClientInfo("jburrow-cli", "0.2.0", "linux", "amd64");

        assertThat(info.name()).isEqualTo("jburrow-cli");
        assertThat(info.os()).isEqualTo("linux");
    }

    @Test
    void osAndArchAreOptional() {
        assertThat(new ClientInfo("jburrow-cli", "0.2.0", null, null).os()).isNull();
    }

    @Test
    void nameAndVersionAreRequired() {
        assertInvalid(() -> new ClientInfo(null, "0.2.0", null, null), "name is required");
        assertInvalid(() -> new ClientInfo("jburrow-cli", null, null, null), "version is required");
    }

    @Test
    void nameIsOneToSixtyFourCharacters() {
        new ClientInfo("a", "1", null, null);
        new ClientInfo("a".repeat(64), "1", null, null);
        assertInvalid(() -> new ClientInfo("", "1", null, null), NAME_RULE);
        assertInvalid(() -> new ClientInfo("a".repeat(65), "1", null, null), NAME_RULE);
    }

    @Test
    void nameAllowsLettersDigitsDotUnderscoreHyphen() {
        new ClientInfo("Az09._-", "1", null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jburrow cli", "jburrow/cli", "jburrow+cli", "jbürrow"})
    void nameRejectsOtherCharacters(String name) {
        assertInvalid(() -> new ClientInfo(name, "1", null, null), NAME_RULE);
    }

    @Test
    void versionIsOneToSixtyFourCharactersAndAllowsPlus() {
        new ClientInfo("c", "1.0.0+build_7-rc", null, null);
        new ClientInfo("c", "1".repeat(64), null, null);
        assertInvalid(() -> new ClientInfo("c", "", null, null), VERSION_RULE);
        assertInvalid(() -> new ClientInfo("c", "1".repeat(65), null, null), VERSION_RULE);
        assertInvalid(() -> new ClientInfo("c", "1 0", null, null), VERSION_RULE);
    }

    @Test
    void osAndArchAreOneToThirtyTwoLowerCaseCharacters() {
        new ClientInfo("c", "1", "a".repeat(32), "x86_64");
        assertInvalid(() -> new ClientInfo("c", "1", "a".repeat(33), null), "os must match [a-z0-9_]{1,32}");
        assertInvalid(() -> new ClientInfo("c", "1", "", null), "os must match [a-z0-9_]{1,32}");
        assertInvalid(() -> new ClientInfo("c", "1", "Linux", null), "os must match [a-z0-9_]{1,32}");
        assertInvalid(() -> new ClientInfo("c", "1", null, "x86-64"), "arch must match [a-z0-9_]{1,32}");
    }
}
