package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TunnelRequestTest {

    private static final String REF_RULE = "ref must match [a-z0-9][a-z0-9-]{0,31}";
    private static final String SUBDOMAIN_RULE =
            "subdomain must be 3 to 63 characters of [a-z0-9-], starting and ending with a letter or digit, without --";

    @Test
    void acceptsTheArchitectureExample() {
        TunnelRequest request = new TunnelRequest("default", TunnelKind.TCP, 20_417);

        assertThat(request.remotePort()).isEqualTo(20_417);
    }

    @Test
    void remotePortIsOptional() {
        assertThat(new TunnelRequest("default", TunnelKind.TCP, null).remotePort())
                .isNull();
    }

    @Test
    void refAndKindAreRequired() {
        assertInvalid(() -> new TunnelRequest(null, TunnelKind.TCP, null), "ref is required");
        assertInvalid(() -> new TunnelRequest("default", null, null), "kind is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "0", "ssh-1", "a-", "abcdefghijklmnopqrstuvwxyz012345"})
    void acceptsValidRefs(String ref) {
        new TunnelRequest(ref, TunnelKind.TCP, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-a", "Default", "a_b", "a.b", "abcdefghijklmnopqrstuvwxyz0123456"})
    void rejectsInvalidRefs(String ref) {
        assertInvalid(() -> new TunnelRequest(ref, TunnelKind.TCP, null), REF_RULE);
    }

    @Test
    void anHttpTunnelMayAskForASubdomain() {
        assertThat(new TunnelRequest("default", TunnelKind.HTTP, "myapp", null).subdomain())
                .isEqualTo("myapp");
        assertThat(new TunnelRequest("default", TunnelKind.HTTP, null, null).subdomain())
                .isNull();
    }

    @Test
    void subdomainIsOnlyForHttpAndRemotePortOnlyForTcp() {
        assertInvalid(
                () -> new TunnelRequest("a", TunnelKind.TCP, "myapp", null),
                "subdomain is only allowed with kind http");
        assertInvalid(
                () -> new TunnelRequest("a", TunnelKind.HTTP, null, 20_417),
                "remotePort is only allowed with kind tcp");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "myapp", "quiet-otter-4f2a", "a-b", "123", "a1-2b-3c", "x2y"})
    void acceptsSubdomainsOf3To63Characters(String subdomain) {
        new TunnelRequest("a", TunnelKind.HTTP, subdomain, null);
    }

    @Test
    void subdomainsAreAtMost63Characters() {
        new TunnelRequest("a", TunnelKind.HTTP, "a".repeat(63), null);
        assertInvalid(() -> new TunnelRequest("a", TunnelKind.HTTP, "a".repeat(64), null), SUBDOMAIN_RULE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "ab", "-abc", "abc-", "my--app", "MyApp", "my_app", "my.app", "my app", "café"})
    void rejectsOtherSubdomains(String subdomain) {
        assertInvalid(() -> new TunnelRequest("a", TunnelKind.HTTP, subdomain, null), SUBDOMAIN_RULE);
    }

    @Test
    void remotePortIsOneTo65535() {
        new TunnelRequest("a", TunnelKind.TCP, 1);
        new TunnelRequest("a", TunnelKind.TCP, 65_535);
        String rule = "remotePort must be between 1 and 65535";
        assertInvalid(() -> new TunnelRequest("a", TunnelKind.TCP, 0), rule);
        assertInvalid(() -> new TunnelRequest("a", TunnelKind.TCP, 65_536), rule);
    }
}
