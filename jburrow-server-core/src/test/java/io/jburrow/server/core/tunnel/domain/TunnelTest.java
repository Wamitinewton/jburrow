package io.jburrow.server.core.tunnel.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.TunnelKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TunnelTest {

    private static final SubdomainName DEMO = new SubdomainName("quiet-otter-4f2a");

    @Test
    void becomesARegisterReplyEntry() {
        Tunnel tunnel = Tunnel.tcp(TunnelId.random(), "default", "tcp.example.dev", 20_417);

        RegisteredTunnel registered = tunnel.toRegistered();

        assertThat(registered.ref()).isEqualTo("default");
        assertThat(registered.tunnelId()).isEqualTo(tunnel.id().value());
        assertThat(registered.kind()).isEqualTo(TunnelKind.TCP);
        assertThat(registered.url()).isEqualTo("tcp://tcp.example.dev:20417");
    }

    @ParameterizedTest
    @CsvSource({
        "https, 443,  https://quiet-otter-4f2a.example.dev",
        "https, 8443, https://quiet-otter-4f2a.example.dev:8443",
        "http,  80,   http://quiet-otter-4f2a.example.dev",
        "http,  7080, http://quiet-otter-4f2a.example.dev:7080"
    })
    void anHttpTunnelIsPublicUnderTheDomainWithItsSchemeAndPort(String scheme, int port, String url) {
        Tunnel tunnel = Tunnel.http(TunnelId.random(), "web", DEMO, "example.dev", scheme, port);

        RegisteredTunnel registered = tunnel.toRegistered();

        assertThat(tunnel.subdomain()).isEqualTo(DEMO);
        assertThat(registered.kind()).isEqualTo(TunnelKind.HTTP);
        assertThat(registered.publicHost()).isEqualTo("quiet-otter-4f2a.example.dev");
        assertThat(registered.publicPort()).isEqualTo(port);
        assertThat(registered.url()).isEqualTo(url);
    }

    @Test
    void eachKindKeepsToItsOwnShape() {
        TunnelId id = TunnelId.random();
        assertThatIllegalArgumentException().isThrownBy(() -> Tunnel.http(id, "web", DEMO, "example.dev", "tcp", 443));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Tunnel(id, "ssh", TunnelKind.TCP, "localhost", 22, "tcp", DEMO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Tunnel(id, "web", TunnelKind.HTTP, "other.example.dev", 443, "https", DEMO));
        assertThatNullPointerException()
                .isThrownBy(() -> new Tunnel(id, "web", TunnelKind.HTTP, "x.example.dev", 443, "https", null));
    }

    @Test
    void rejectsAPortOutsideTcp() {
        assertThatIllegalArgumentException().isThrownBy(() -> Tunnel.tcp(TunnelId.random(), "default", "localhost", 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Tunnel.tcp(TunnelId.random(), "default", "localhost", 65_536));
    }

    @Test
    void principalNamesAreNonBlankAndShort() {
        assertThat(new Principal("a".repeat(64)).name()).hasSize(64);
        assertThatIllegalArgumentException().isThrownBy(() -> new Principal(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> new Principal("a".repeat(65)));
    }

    @Test
    void randomIdsDiffer() {
        assertThat(SessionId.random()).isNotEqualTo(SessionId.random());
        assertThat(TunnelId.random()).isNotEqualTo(TunnelId.random());
    }
}
