package io.jburrow.server.core.tunnel.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.TunnelKind;
import org.junit.jupiter.api.Test;

class TunnelTest {

    @Test
    void becomesARegisterReplyEntry() {
        Tunnel tunnel = new Tunnel(TunnelId.random(), "default", TunnelKind.TCP, "tcp.example.dev", 20_417);

        RegisteredTunnel registered = tunnel.toRegistered();

        assertThat(registered.ref()).isEqualTo("default");
        assertThat(registered.tunnelId()).isEqualTo(tunnel.id().value());
        assertThat(registered.url()).isEqualTo("tcp://tcp.example.dev:20417");
    }

    @Test
    void rejectsAPortOutsideTcp() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Tunnel(TunnelId.random(), "default", TunnelKind.TCP, "localhost", 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Tunnel(TunnelId.random(), "default", TunnelKind.TCP, "localhost", 65_536));
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
