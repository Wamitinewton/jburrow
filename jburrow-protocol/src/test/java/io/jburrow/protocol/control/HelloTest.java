package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HelloTest {

    private static final ClientInfo CLIENT = new ClientInfo("jburrow-cli", "0.2.0", "linux", "amd64");
    private static final Windows WINDOWS = new Windows(262_144, 1_048_576);

    @Test
    void acceptsAnEmptyCapabilityList() {
        assertThat(hello(List.of()).capabilities()).isEmpty();
    }

    @Test
    void clientCapabilitiesAndWindowsAreRequired() {
        assertInvalid(() -> new Hello(null, List.of(), WINDOWS), "client is required");
        assertInvalid(() -> new Hello(CLIENT, null, WINDOWS), "capabilities is required");
        assertInvalid(() -> new Hello(CLIENT, List.of(), null), "windows is required");
    }

    @Test
    void allowsAtMostThirtyTwoCapabilities() {
        hello(capabilities(32));
        assertInvalid(() -> hello(capabilities(33)), "capabilities must have 0 to 32 entries");
    }

    @Test
    void capabilityNamesAreOneToSixtyFourOfLowerCaseDigitDotHyphen() {
        hello(List.of("a", "flow.control-2", "c".repeat(64)));
        String rule = "capabilities[1] must match [a-z0-9.-]{1,64}";
        assertInvalid(() -> hello(List.of("a", "")), rule);
        assertInvalid(() -> hello(List.of("a", "c".repeat(65))), rule);
        assertInvalid(() -> hello(List.of("a", "Flow")), rule);
        assertInvalid(() -> hello(List.of("a", "flow_control")), rule);
    }

    @Test
    void capabilitiesMustNotRepeat() {
        assertInvalid(() -> hello(List.of("a", "b", "a")), "capabilities[2] must be unique");
    }

    @Test
    void capabilitiesMustNotContainNull() {
        assertInvalid(() -> hello(Arrays.asList("a", null)), "capabilities[1] is required");
    }

    @Test
    void capabilitiesAreAnImmutableCopy() {
        List<String> source = new ArrayList<>(List.of("a"));
        Hello hello = hello(source);

        source.add("b");

        assertThat(hello.capabilities()).containsExactly("a").isUnmodifiable();
    }

    private static Hello hello(List<String> capabilities) {
        return new Hello(CLIENT, capabilities, WINDOWS);
    }

    private static List<String> capabilities(int count) {
        return IntStream.range(0, count).mapToObj(i -> "cap" + i).toList();
    }
}
