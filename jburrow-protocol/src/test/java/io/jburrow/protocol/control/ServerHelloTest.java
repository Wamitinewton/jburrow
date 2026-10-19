package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServerHelloTest {

    private static final UUID SESSION_ID = UUID.fromString("0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a");
    private static final ServerInfo SERVER = new ServerInfo("jburrow-server", "0.2.0");
    private static final SessionLimits LIMITS = new SessionLimits(65_536, 1, 1, 15_000, 45_000);

    @Test
    void acceptsTheArchitectureExample() {
        assertThat(new ServerHello(SESSION_ID, SERVER, List.of(), LIMITS).sessionId())
                .isEqualTo(SESSION_ID);
    }

    @Test
    void everyFieldIsRequired() {
        assertInvalid(() -> new ServerHello(null, SERVER, List.of(), LIMITS), "sessionId is required");
        assertInvalid(() -> new ServerHello(SESSION_ID, null, List.of(), LIMITS), "server is required");
        assertInvalid(() -> new ServerHello(SESSION_ID, SERVER, null, LIMITS), "capabilities is required");
        assertInvalid(() -> new ServerHello(SESSION_ID, SERVER, List.of(), null), "limits is required");
    }

    @Test
    void capabilitiesFollowTheHelloRules() {
        assertInvalid(
                () -> new ServerHello(SESSION_ID, SERVER, List.of("x", "x"), LIMITS), "capabilities[1] must be unique");
        assertInvalid(
                () -> new ServerHello(SESSION_ID, SERVER, List.of("X"), LIMITS),
                "capabilities[0] must match [a-z0-9.-]{1,64}");
    }

    @Test
    void capabilitiesAreAnImmutableCopy() {
        List<String> source = new ArrayList<>(List.of("a"));
        ServerHello hello = new ServerHello(SESSION_ID, SERVER, source, LIMITS);

        source.clear();

        assertThat(hello.capabilities()).containsExactly("a").isUnmodifiable();
    }
}
