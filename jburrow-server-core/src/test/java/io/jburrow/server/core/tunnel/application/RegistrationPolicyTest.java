package io.jburrow.server.core.tunnel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.control.TunnelRequest;
import io.jburrow.server.core.portpool.application.port.Reservation;
import io.jburrow.server.core.tunnel.domain.TunnelId;
import org.junit.jupiter.api.Test;

class RegistrationPolicyTest {

    private static final RegistrationPolicy PRODUCTION = new RegistrationPolicy(
            1, "tcp.example.dev", new RegistrationPolicy.HttpPublic("example.dev", "https", 443));

    @Test
    void anHttpTunnelIsPublicOnTheHttpTermsWithNoPortOfItsOwn() {
        PlannedTunnel.Http planned = new PlannedTunnel.Http(
                new TunnelRequest("web", TunnelKind.HTTP, "demo", null), new SubdomainName("demo"), TunnelId.random());

        assertThat(planned.toTunnel(PRODUCTION).toRegistered().url()).isEqualTo("https://demo.example.dev");
        assertThat(planned.toTunnel(RegistrationFixtures.policy(1, "localhost"))
                        .toRegistered()
                        .url())
                .isEqualTo("http://demo.localhost:7080");
    }

    @Test
    void aTcpTunnelIsPublicOnTheTcpHostAtItsReservedPort() {
        PlannedTunnel.Tcp planned = new PlannedTunnel.Tcp(
                new TunnelRequest("ssh", TunnelKind.TCP, null), new Reservation(20_417, 1), TunnelId.random());

        assertThat(planned.toTunnel(PRODUCTION).toRegistered().url()).isEqualTo("tcp://tcp.example.dev:20417");
    }

    @Test
    void aPlannedTunnelMatchesItsKind() {
        TunnelRequest http = new TunnelRequest("web", TunnelKind.HTTP, null, null);
        TunnelRequest tcp = new TunnelRequest("ssh", TunnelKind.TCP, null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PlannedTunnel.Tcp(http, new Reservation(20_417, 1), TunnelId.random()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PlannedTunnel.Http(tcp, new SubdomainName("demo"), TunnelId.random()));
    }

    @Test
    void theHttpTermsAreChecked() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RegistrationPolicy.HttpPublic("example.dev", "ftp", 443));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RegistrationPolicy.HttpPublic("example.dev", "https", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new RegistrationPolicy.HttpPublic(" ", "https", 443));
        assertThatNullPointerException().isThrownBy(() -> new RegistrationPolicy(1, "localhost", null));
    }
}
