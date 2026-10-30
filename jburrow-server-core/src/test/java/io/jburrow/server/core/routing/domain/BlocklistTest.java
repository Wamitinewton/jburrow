package io.jburrow.server.core.routing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.control.SubdomainName;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlocklistTest {

    private final Blocklist defaults = Blocklist.defaults(Set.of());

    @ParameterizedTest
    @ValueSource(strings = {"api", "app", "connect", "tcp", "www", "mail", "admin", "status", "docs", "abuse", "root"})
    void refusesReservedNames(String name) {
        assertThat(defaults.allows(new SubdomainName(name))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"api-demo", "my-app", "tcp-relay", "mailbox", "docs2", "rooted"})
    void allowsNamesThatOnlyContainAReservedName(String name) {
        assertThat(defaults.allows(new SubdomainName(name))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"login", "paypal-login", "secure-pay", "mybank", "accounting-demo", "x-mpesa-x", "office365-sso"
            })
    void refusesBlockedTermsAnywhere(String name) {
        assertThat(defaults.allows(new SubdomainName(name))).isFalse();
    }

    @Test
    void addsConfiguredReservedNames() {
        Blocklist blocklist = Blocklist.defaults(Set.of("dashboard"));

        assertThat(blocklist.allows(new SubdomainName("dashboard"))).isFalse();
        assertThat(blocklist.allows(new SubdomainName("api"))).isFalse();
        assertThat(blocklist.allows(new SubdomainName("dashboards"))).isTrue();
        assertThat(defaults.allows(new SubdomainName("dashboard"))).isTrue();
    }

    @Test
    void allowsOrdinaryNames() {
        assertThat(defaults.allows(new SubdomainName("quiet-otter-4f2a"))).isTrue();
        assertThat(defaults.allows(new SubdomainName("my-demo"))).isTrue();
    }

    @Test
    void builtInListsAreSortedAndUnique() {
        assertThat(Blocklist.RESERVED_NAMES).isSorted().doesNotHaveDuplicates();
        assertThat(Blocklist.BLOCKED_TERMS).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void customListsAreUsedAsGiven() {
        Blocklist blocklist = new Blocklist(Set.of("abc"), Set.of("xyz"));

        assertThat(blocklist.allows(new SubdomainName("abc"))).isFalse();
        assertThat(blocklist.allows(new SubdomainName("a-xyz-b"))).isFalse();
        assertThat(blocklist.allows(new SubdomainName("api"))).isTrue();
    }
}
