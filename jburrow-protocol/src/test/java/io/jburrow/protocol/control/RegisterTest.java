package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RegisterTest {

    private static final String TOKEN = "jb_LOCAL-DEVELOPMENT-ONLY-unlocks-localhost-00";
    private static final TunnelRequest DEFAULT = new TunnelRequest("default", TunnelKind.TCP, 20_417);

    @Test
    void acceptsTheArchitectureExample() {
        Register register = new Register(TOKEN, List.of(DEFAULT));

        assertThat(register.authtoken()).isEqualTo(TOKEN);
        assertThat(register.tunnels()).containsExactly(DEFAULT);
    }

    @Test
    void authtokenIsOneTo256Characters() {
        new Register("x", List.of(DEFAULT));
        new Register("x".repeat(256), List.of(DEFAULT));
        assertInvalid(() -> new Register(null, List.of(DEFAULT)), "authtoken is required");
        assertInvalid(() -> new Register("", List.of(DEFAULT)), "authtoken must be 1 to 256 characters");
        assertInvalid(() -> new Register("x".repeat(257), List.of(DEFAULT)), "authtoken must be 1 to 256 characters");
    }

    @Test
    void tunnelsHoldOneToThirtyTwoEntries() {
        new Register(TOKEN, tunnels(32));
        assertInvalid(() -> new Register(TOKEN, null), "tunnels is required");
        assertInvalid(() -> new Register(TOKEN, List.of()), "tunnels must have 1 to 32 entries");
        assertInvalid(() -> new Register(TOKEN, tunnels(33)), "tunnels must have 1 to 32 entries");
        assertInvalid(() -> new Register(TOKEN, Arrays.asList(DEFAULT, null)), "tunnels[1] is required");
    }

    @Test
    void refsAreUnique() {
        TunnelRequest again = new TunnelRequest("default", TunnelKind.TCP, null);

        assertInvalid(() -> new Register(TOKEN, List.of(DEFAULT, again)), "tunnels[1].ref must be unique");
    }

    @Test
    void tunnelsAreAnImmutableCopy() {
        List<TunnelRequest> source = new ArrayList<>(List.of(DEFAULT));
        Register register = new Register(TOKEN, source);

        source.clear();

        assertThat(register.tunnels()).containsExactly(DEFAULT).isUnmodifiable();
    }

    @Test
    void toStringRedactsAPrefixedToken() {
        String text = new Register(TOKEN, List.of(DEFAULT)).toString();

        assertThat(text).contains("authtoken=jb_***").contains("default").doesNotContain("LOCAL-DEVELOPMENT");
    }

    @Test
    void toStringRedactsAnyOtherToken() {
        String text = new Register("s3cr3t-value", List.of(DEFAULT)).toString();

        assertThat(text).contains("authtoken=***").doesNotContain("s3cr3t");
    }

    @Test
    void toStringRedactsATokenThatIsOnlyThePrefix() {
        assertThat(new Register("jb_", List.of(DEFAULT)).toString()).contains("authtoken=jb_***");
    }

    private static List<TunnelRequest> tunnels(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new TunnelRequest("t" + i, TunnelKind.TCP, null))
                .toList();
    }
}
