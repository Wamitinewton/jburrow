package io.jburrow.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.wire.Capability;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.tunnel.adapter.netty.edge.Cidr;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeSettings;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.jburrow.server.core.tunnel.adapter.netty.edge.HttpSettings;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

class EdgeConfigurationTest {

    private static final PortRange RANGE = new PortRange(21_000, 21_099);

    @Test
    void buildsEdgeSettingsFromTheProperties() {
        EdgeSettings settings =
                EdgeConfiguration.edgeSettings(edge("0.0.0.0", List.of("10.42.0.0/16")), http(List.of()), "1.2.3");

        assertThat(settings.bind().getAddress().getHostAddress()).isEqualTo("0.0.0.0");
        assertThat(settings.bind().getPort()).isEqualTo(7080);
        assertThat(settings.connectHost()).isEqualTo("connect.example.dev");
        assertThat(settings.transport()).isEqualTo(EdgeTransport.NIO);
        assertThat(settings.ioThreads()).isEqualTo(4);
        assertThat(settings.trustedProxies()).containsExactly(Cidr.parse("10.42.0.0/16"));
        assertThat(settings.sessionWriteLow()).isEqualTo(64 * 1024);
        assertThat(settings.sessionWriteHigh()).isEqualTo(256 * 1024);
        assertThat(settings.windows()).isEqualTo(new Windows(256 * 1024, 512 * 1024));
        assertThat(settings.maxConcurrentStreams()).isEqualTo(1_000);
        assertThat(settings.maxStreams()).isEqualTo(4_000);
        assertThat(settings.serverVersion()).isEqualTo("1.2.3");
        assertThat(settings.http())
                .isEqualTo(new HttpSettings(
                        "example.dev",
                        100L * 1024 * 1024,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(75),
                        16 * 1024,
                        Duration.ofSeconds(1),
                        16L * 1024 * 1024,
                        2L * 1024 * 1024));
        assertThat(settings.capabilities()).containsExactly(Capability.HTTP);
    }

    @Test
    void reservesTheLabelsOfTheServiceHostsUnderTheDomain() {
        assertThat(EdgeConfiguration.reservedNames(http(List.of("grafana")), "connect.example.dev", "TCP.example.dev"))
                .containsExactly("connect", "grafana", "tcp");
    }

    @Test
    void reservesNothingForServiceHostsOutsideTheDomain() {
        assertThat(EdgeConfiguration.reservedNames(http(List.of()), "connect.other.dev", "example.dev"))
                .isEmpty();
    }

    @Test
    void aServiceHostTwoLabelsUnderTheDomainIsRefused() {
        assertThatIllegalStateException()
                .isThrownBy(() ->
                        EdgeConfiguration.reservedNames(http(List.of()), "connect.example.dev", "tcp.eu.example.dev"))
                .withMessageStartingWith("Invalid jburrow.tcp.public-host: tcp.eu.example.dev is more than one label");
        assertThatIllegalStateException()
                .isThrownBy(() -> EdgeConfiguration.reservedNames(http(List.of()), "a.b.example.dev", "localhost"))
                .withMessageStartingWith("Invalid jburrow.edge.connect-host");
    }

    @Test
    void anInvalidTrustedProxyFailsNamingTheKeyAndEntry() {
        assertThatIllegalStateException()
                .isThrownBy(() -> EdgeConfiguration.trustedProxies(List.of("10.42.0.0/16", "ingress.local/16")))
                .withMessageStartingWith(
                        "Invalid jburrow.edge.trusted-proxies (JBURROW_EDGE_TRUSTED_PROXIES), entry 2");
        assertThatIllegalStateException()
                .isThrownBy(() -> EdgeConfiguration.trustedProxies(List.of("10.42.0.7/16")))
                .withMessageContaining("entry 1");
    }

    @Test
    void aBindAddressMustBeAnIpLiteral() {
        assertThatIllegalStateException()
                .isThrownBy(() -> EdgeConfiguration.edgeSettings(edge("localhost", List.of()), http(List.of()), "1"))
                .withMessage("Invalid jburrow.edge.bind-address: must be an IP address literal");
    }

    @Test
    void thePortRangeMustNotContainTheApiOrManagementPort() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.port", "21010")
                .withProperty("management.server.port", "7091");

        EdgeConfiguration.requireClear(RANGE, environment, "management.server.port");
        assertThatIllegalStateException()
                .isThrownBy(() -> EdgeConfiguration.requireClear(RANGE, environment, "server.port"))
                .withMessage("Invalid jburrow.tcp.port-range: 21000-21099 contains server.port 21010");
    }

    @Test
    void anUnsetPortIsClear() {
        EdgeConfiguration.requireClear(RANGE, new MockEnvironment(), "server.port");
    }

    private static JBurrowProperties.Http http(List<String> reservedNames) {
        return new JBurrowProperties.Http(
                "example.dev",
                "https",
                443,
                reservedNames,
                Duration.ofSeconds(60),
                DataSize.ofMegabytes(100),
                Duration.ofSeconds(60),
                Duration.ofSeconds(75),
                DataSize.ofKilobytes(16),
                Duration.ofSeconds(1),
                DataSize.ofMegabytes(16),
                DataSize.ofMegabytes(2));
    }

    private static JBurrowProperties.Edge edge(String bindAddress, List<String> trustedProxies) {
        return new JBurrowProperties.Edge(
                7080,
                bindAddress,
                "connect.example.dev",
                EdgeTransport.NIO,
                4,
                trustedProxies,
                200,
                64,
                3,
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(45),
                "64KB-256KB",
                DataSize.ofKilobytes(256),
                DataSize.ofKilobytes(512),
                1_000,
                4_000);
    }
}
