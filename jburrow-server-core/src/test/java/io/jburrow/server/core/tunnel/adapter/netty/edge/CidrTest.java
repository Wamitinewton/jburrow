package io.jburrow.server.core.tunnel.adapter.netty.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CidrTest {

    @Test
    void parsesIpv4() {
        Cidr cidr = Cidr.parse("10.42.0.0/16");

        assertThat(cidr.prefixLength()).isEqualTo(16);
        assertThat(cidr).hasToString("10.42.0.0/16").isEqualTo(Cidr.parse("10.42.0.0/16"));
    }

    @Test
    void parsesIpv6() {
        Cidr cidr = Cidr.parse("fd00:42::/32");

        assertThat(cidr.prefixLength()).isEqualTo(32);
        assertThat(cidr.contains(address("fd00:42::1"))).isTrue();
        assertThat(cidr.contains(address("fd00:43::1"))).isFalse();
    }

    @Test
    void containsAddressesAtTheEdgesOfTheIpv4Prefix() {
        Cidr cidr = Cidr.parse("10.42.0.0/16");

        assertThat(cidr.contains(address("10.42.0.0"))).isTrue();
        assertThat(cidr.contains(address("10.42.255.255"))).isTrue();
        assertThat(cidr.contains(address("10.41.255.255"))).isFalse();
        assertThat(cidr.contains(address("10.43.0.0"))).isFalse();
    }

    @Test
    void handlesAPrefixThatEndsInsideAByte() {
        Cidr cidr = Cidr.parse("192.168.4.0/22");

        assertThat(cidr.contains(address("192.168.4.0"))).isTrue();
        assertThat(cidr.contains(address("192.168.7.255"))).isTrue();
        assertThat(cidr.contains(address("192.168.3.255"))).isFalse();
        assertThat(cidr.contains(address("192.168.8.0"))).isFalse();
    }

    @Test
    void handlesTheWholeRangeAndSingleHosts() {
        assertThat(Cidr.parse("0.0.0.0/0").contains(address("203.0.113.9"))).isTrue();
        assertThat(Cidr.parse("127.0.0.1/32").contains(address("127.0.0.1"))).isTrue();
        assertThat(Cidr.parse("127.0.0.1/32").contains(address("127.0.0.2"))).isFalse();
        assertThat(Cidr.parse("::1/128").contains(address("::1"))).isTrue();
        assertThat(Cidr.parse("::/0").contains(address("2001:db8::1"))).isTrue();
    }

    @Test
    void neverMatchesAcrossAddressFamilies() {
        assertThat(Cidr.parse("::/0").contains(address("10.0.0.1"))).isFalse();
        assertThat(Cidr.parse("0.0.0.0/0").contains(address("::1"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.42.0.7/16", "10.43.0.0/15", "192.168.5.0/22", "fd00:42::1/32", "::1/0"})
    void refusesHostBitsBeyondThePrefix(String text) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Cidr.parse(text))
                .withMessageContaining("beyond its prefix");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost/32", "example.dev/24", "10.42.0/16", "10.42.0.0.0/16", ""})
    void refusesAnythingButAnAddressLiteral(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> Cidr.parse(text));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"10.0.0.0", "10.0.0.0/", "10.0.0.0/33", "::/129", "10.0.0.0/-1", "10.0.0.0/+8", "10.0.0.0/a"})
    void refusesAMissingOrInvalidPrefix(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> Cidr.parse(text));
    }

    @Test
    void parsesAddressLiteralsWithoutResolvingNames() {
        assertThat(Cidr.parseAddress("203.0.113.9")).contains(address("203.0.113.9"));
        assertThat(Cidr.parseAddress("2001:db8::1")).contains(address("2001:db8::1"));
        assertThat(Cidr.parseAddress("localhost")).isEmpty();
        assertThat(Cidr.parseAddress("1.2.3.4:5678")).isEmpty();
        assertThat(Cidr.parseAddress(null)).isEmpty();
    }

    private static InetAddress address(String literal) {
        return Cidr.parseAddress(literal).orElseThrow();
    }
}
