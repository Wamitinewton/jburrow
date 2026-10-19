package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TunnelRequestTest {

    private static final String REF_RULE = "ref must match [a-z0-9][a-z0-9-]{0,31}";

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
    void remotePortIsOneTo65535() {
        new TunnelRequest("a", TunnelKind.TCP, 1);
        new TunnelRequest("a", TunnelKind.TCP, 65_535);
        String rule = "remotePort must be between 1 and 65535";
        assertInvalid(() -> new TunnelRequest("a", TunnelKind.TCP, 0), rule);
        assertInvalid(() -> new TunnelRequest("a", TunnelKind.TCP, 65_536), rule);
    }
}
