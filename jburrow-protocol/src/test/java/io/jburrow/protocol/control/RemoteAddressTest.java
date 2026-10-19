package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteAddressTest {

    private static final String ADDRESS_RULE = "address must be an IPv4 or IPv6 address literal";

    @Test
    void acceptsTheArchitectureExample() {
        assertThat(new RemoteAddress("203.0.113.9", 51_234).port()).isEqualTo(51_234);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "255.255.255.255", "203.0.113.9", "10.0.0.1", "192.168.100.199"})
    void acceptsIpv4DottedQuads(String address) {
        new RemoteAddress(address, 1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "256.0.0.1",
                "1.2.3",
                "1.2.3.4.5",
                "01.2.3.4",
                "1.2.3.04",
                "1.2.3.-1",
                "1..2.3",
                " 1.2.3.4",
                "1.2.3.4 "
            })
    void rejectsMalformedIpv4(String address) {
        assertInvalid(() -> new RemoteAddress(address, 1), ADDRESS_RULE);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "::",
                "::1",
                "1::",
                "2001:db8::1",
                "2001:DB8::A",
                "2001:db8:0:0:0:0:2:1",
                "fe80::1:2:3:4:5:6",
                "1:2:3:4:5:6:7::",
                "::ffff:203.0.113.9",
                "64:ff9b::192.0.2.33",
                "1:2:3:4:5:6:1.2.3.4",
                "0:0:0:0:0:0:0:1"
            })
    void acceptsIpv6Literals(String address) {
        new RemoteAddress(address, 1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                ":",
                ":1",
                "1:",
                ":::",
                "1:::2",
                "1::2::3",
                "1:2:3:4:5:6:7",
                "1:2:3:4:5:6:7:8:9",
                "1:2:3:4:5:6:7:8::",
                "12345::",
                "g::1",
                "[::1]",
                "fe80::1%eth0",
                "1.2.3.4::",
                "::1.2.3",
                "1:2:3:4:5:6:7:1.2.3.4",
                "::ffff:256.0.0.1"
            })
    void rejectsMalformedIpv6(String address) {
        assertInvalid(() -> new RemoteAddress(address, 1), ADDRESS_RULE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "example.com", "", " "})
    void neverAcceptsAHostName(String address) {
        assertInvalid(() -> new RemoteAddress(address, 1), ADDRESS_RULE);
    }

    @Test
    void rejectsAnOverlongCandidateWithoutParsingIt() {
        assertInvalid(() -> new RemoteAddress("1:".repeat(2_000) + "1", 1), ADDRESS_RULE);
    }

    @Test
    void addressIsRequired() {
        assertInvalid(() -> new RemoteAddress(null, 1), "address is required");
    }

    @Test
    void portIsZeroTo65535() {
        new RemoteAddress("::1", 0);
        new RemoteAddress("::1", 65_535);
        assertInvalid(() -> new RemoteAddress("::1", -1), "port must be between 0 and 65535");
        assertInvalid(() -> new RemoteAddress("::1", 65_536), "port must be between 0 and 65535");
    }
}
