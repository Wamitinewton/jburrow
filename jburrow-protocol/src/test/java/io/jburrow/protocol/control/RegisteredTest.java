package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RegisteredTest {

    private static final RegisteredTunnel DEFAULT = tunnel("default");

    @Test
    void holdsOneToThirtyTwoTunnels() {
        new Registered(tunnels(32));
        assertInvalid(() -> new Registered(null), "tunnels is required");
        assertInvalid(() -> new Registered(List.of()), "tunnels must have 1 to 32 entries");
        assertInvalid(() -> new Registered(tunnels(33)), "tunnels must have 1 to 32 entries");
    }

    @Test
    void resumeTokenIsOptional() {
        ResumeToken token = new ResumeToken(ResumeTokenTest.EXAMPLE);

        assertThat(new Registered(List.of(DEFAULT)).resumeToken()).isNull();
        assertThat(new Registered(List.of(DEFAULT), token).resumeToken()).isEqualTo(token);
        assertThat(new Registered(List.of(DEFAULT), null)).isEqualTo(new Registered(List.of(DEFAULT)));
    }

    @Test
    void toStringMasksTheResumeToken() {
        String text = new Registered(List.of(DEFAULT), new ResumeToken(ResumeTokenTest.EXAMPLE)).toString();

        assertThat(text).contains("ResumeToken[jbr_***]").doesNotContain(ResumeTokenTest.EXAMPLE.substring(4));
    }

    @Test
    void refsAreUnique() {
        assertInvalid(() -> new Registered(List.of(DEFAULT, tunnel("default"))), "tunnels[1].ref must be unique");
    }

    @Test
    void tunnelsAreAnImmutableCopy() {
        List<RegisteredTunnel> source = new ArrayList<>(List.of(DEFAULT));
        Registered registered = new Registered(source);

        source.clear();

        assertThat(registered.tunnels()).containsExactly(DEFAULT).isUnmodifiable();
    }

    private static List<RegisteredTunnel> tunnels(int count) {
        return IntStream.range(0, count).mapToObj(i -> tunnel("t" + i)).toList();
    }

    private static RegisteredTunnel tunnel(String ref) {
        return RegisteredTunnel.of(ref, UUID.randomUUID(), TunnelKind.TCP, "tcp.cloudnativehub.app", 20_417);
    }
}
