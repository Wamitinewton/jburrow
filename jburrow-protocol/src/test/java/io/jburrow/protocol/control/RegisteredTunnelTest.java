package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegisteredTunnelTest {

    private static final UUID TUNNEL_ID = UUID.fromString("5d0f4f0e-31a6-4a39-8d51-0c0f7f1f6c23");
    private static final String HOST_RULE =
            "publicHost must be a lower-case DNS name or IPv4 address of at most 253 characters";

    @Test
    void factoryBuildsTheUrl() {
        RegisteredTunnel tunnel =
                RegisteredTunnel.of("default", TUNNEL_ID, TunnelKind.TCP, "tcp.cloudnativehub.app", 20_417);

        assertThat(tunnel.url()).isEqualTo("tcp://tcp.cloudnativehub.app:20417");
    }

    @Test
    void urlMustMatchKindHostAndPort() {
        new RegisteredTunnel("d", TUNNEL_ID, TunnelKind.TCP, "localhost", 21_037, "tcp://localhost:21037");
        String rule = "url must be <kind>://<publicHost>:<publicPort>";
        assertInvalid(
                () -> new RegisteredTunnel(
                        "d", TUNNEL_ID, TunnelKind.TCP, "localhost", 21_037, "tcp://localhost:21038"),
                rule);
        assertInvalid(
                () -> new RegisteredTunnel(
                        "d", TUNNEL_ID, TunnelKind.TCP, "localhost", 21_037, "TCP://localhost:21037"),
                rule);
        assertInvalid(
                () -> new RegisteredTunnel("d", TUNNEL_ID, TunnelKind.TCP, "localhost", 1, null), "url is required");
    }

    @Test
    void everyOtherFieldIsRequired() {
        assertInvalid(() -> RegisteredTunnel.of(null, TUNNEL_ID, TunnelKind.TCP, "h", 1), "ref is required");
        assertInvalid(() -> RegisteredTunnel.of("d", null, TunnelKind.TCP, "h", 1), "tunnelId is required");
        assertInvalid(() -> new RegisteredTunnel("d", TUNNEL_ID, null, "h", 1, "tcp://h:1"), "kind is required");
        assertInvalid(() -> RegisteredTunnel.of("d", TUNNEL_ID, TunnelKind.TCP, null, 1), "publicHost is required");
    }

    @Test
    void refFollowsTheRequestRule() {
        assertInvalid(
                () -> RegisteredTunnel.of("Default", TUNNEL_ID, TunnelKind.TCP, "h", 1),
                "ref must match [a-z0-9][a-z0-9-]{0,31}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "tcp.cloudnativehub.app", "a", "x-1.example.dev", "203.0.113.9"})
    void acceptsLowerCaseHostNamesAndIpv4(String host) {
        RegisteredTunnel.of("d", TUNNEL_ID, TunnelKind.TCP, host, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "TCP.example.dev", "-a.example", "a-.example", "a..b", "a.", ".a", "a_b", "::1", "a b"})
    void rejectsOtherHosts(String host) {
        assertInvalid(() -> RegisteredTunnel.of("d", TUNNEL_ID, TunnelKind.TCP, host, 1), HOST_RULE);
    }

    @Test
    void hostLabelsAreAtMostSixtyThreeCharacters() {
        RegisteredTunnel.of("d", TUNNEL_ID, TunnelKind.TCP, "a".repeat(63) + ".dev", 1);
        assertInvalid(() -> RegisteredTunnel.of("d", TUNNEL_ID, TunnelKind.TCP, "a".repeat(64) + ".dev", 1), HOST_RULE);
    }

    @Test
    void hostIsAtMost253Characters() {
        String label = "a".repeat(61);
        String host253 = String.join(".", label, label, label, label) + ".abcde";
        assertThat(host253).hasSize(253);

        RegisteredTunnel.of("d", TUNNEL_ID, TunnelKind.TCP, host253, 1);
        assertInvalid(() -> RegisteredTunnel.of("d", TUNNEL_ID, TunnelKind.TCP, host253 + "i", 1), HOST_RULE);
    }

    @Test
    void publicPortIsOneTo65535() {
        RegisteredTunnel.of("d", TUNNEL_ID, TunnelKind.TCP, "h", 65_535);
        String rule = "publicPort must be between 1 and 65535";
        assertInvalid(() -> RegisteredTunnel.of("d", TUNNEL_ID, TunnelKind.TCP, "h", 0), rule);
        assertInvalid(() -> RegisteredTunnel.of("d", TUNNEL_ID, TunnelKind.TCP, "h", 65_536), rule);
    }
}
