package io.jburrow.protocol.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SubdomainNameTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc", "quiet-otter-4f2a", "a1b", "123", "a-b", "x0-0-0y"})
    void acceptsValidLabels(String label) {
        assertThat(SubdomainName.parse(label)).contains(new SubdomainName(label));
    }

    @Test
    void acceptsThreeAndSixtyThreeCharacters() {
        assertThat(SubdomainName.parse("a".repeat(3))).isPresent();
        assertThat(SubdomainName.parse("a".repeat(63))).isPresent();
    }

    @Test
    void refusesTwoAndSixtyFourCharacters() {
        assertThat(SubdomainName.parse("a".repeat(2))).isEmpty();
        assertThat(SubdomainName.parse("a".repeat(64))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "-abc",
                "abc-",
                "ab--cd",
                "Quiet-otter",
                "QUIET",
                "a.bc",
                "abc.",
                "ab_c",
                "ab c",
                " abc",
                "ünï"
            })
    void refusesInvalidLabels(String label) {
        assertThat(SubdomainName.parse(label)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> new SubdomainName(label));
    }

    @Test
    void findsTheLabelOneLevelUnderTheDomain() {
        assertThat(SubdomainName.underDomain("quiet-otter-4f2a.cloudnativehub.app", "cloudnativehub.app"))
                .contains(new SubdomainName("quiet-otter-4f2a"));
        assertThat(SubdomainName.underDomain("quiet-otter-4f2a.localhost", "localhost"))
                .contains(new SubdomainName("quiet-otter-4f2a"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "cloudnativehub.app",
                ".cloudnativehub.app",
                "a.b-cd.cloudnativehub.app",
                "abc.def.cloudnativehub.app",
                "abccloudnativehub.app",
                "abc.cloudnativehub.app.",
                "abc.other.app",
                "ab.cloudnativehub.app",
                "ABC.cloudnativehub.app",
                "abc.cloudnativehub.app:443"
            })
    void refusesHostsThatAreNotOneLabelUnderTheDomain(String host) {
        assertThat(SubdomainName.underDomain(host, "cloudnativehub.app")).isEmpty();
    }

    @Test
    void printsAsItsValue() {
        assertThat(new SubdomainName("quiet-otter-4f2a")).hasToString("quiet-otter-4f2a");
    }
}
