package io.jburrow.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jburrow.client.tunnel.application.port.TunnelEvents;
import io.jburrow.client.tunnel.domain.HostHeader;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.wire.Capability;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class JBurrowClientBuilderTest {

    private static final String TOKEN = "jb_" + "a".repeat(43);
    private static final TunnelSpec TUNNEL = TunnelSpec.tcp("default", LocalTarget.of("localhost", 22));

    @ParameterizedTest(name = "{0} is allowed")
    @ValueSource(
            strings = {
                "wss://connect.example.dev/v1/connect",
                "ws://localhost:7080/v1/connect",
                "ws://LOCALHOST:7080/v1/connect",
                "ws://127.0.0.1:7080/v1/connect",
                "ws://127.0.0.2/v1/connect",
                "ws://127.255.255.254/v1/connect",
                "ws://[::1]:7080/v1/connect",
                "WSS://connect.example.dev/v1/connect"
            })
    void allowedUrls(String url) {
        assertThat(settings(url).server().getRawPath()).isEqualTo("/v1/connect");
    }

    @ParameterizedTest(name = "{0} is refused: {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "ws://example.com/v1/connect | only allowed to this machine",
                "ws://localhost.example.com/v1/connect | only allowed to this machine",
                "ws://128.0.0.1/v1/connect | only allowed to this machine",
                "ws://127.0.0.256/v1/connect | needs a host",
                "ws://10.0.0.1/v1/connect | only allowed to this machine",
                "ws://[::2]/v1/connect | only allowed to this machine",
                "ws://[::ffff:127.0.0.1]/v1/connect | only allowed to this machine",
                "https://connect.example.dev/v1/connect | must start with wss://",
                "connect.example.dev/v1/connect | must start with wss://",
                "wss://connect.example.dev/v1/connect/ | path must be /v1/connect",
                "wss://connect.example.dev/ | path must be /v1/connect",
                "wss://connect.example.dev/v2/connect | path must be /v1/connect",
                "wss://user:secret@connect.example.dev/v1/connect | user information",
                "wss://connect.example.dev/v1/connect#here | fragment",
                "wss:///v1/connect | needs a host"
            })
    void refusedUrls(String url, String reason) {
        assertThatThrownBy(() -> settings(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void theStoredUrlCarriesItsPortAndDropsAQuery() {
        assertThat(settings("wss://connect.example.dev/v1/connect?x=1").server())
                .isEqualTo(URI.create("wss://connect.example.dev:443/v1/connect"));
        assertThat(settings("ws://localhost/v1/connect").server())
                .isEqualTo(URI.create("ws://localhost:80/v1/connect"));
        ClientSettings ipv6 = settings("ws://[::1]:7080/v1/connect");
        assertThat(ipv6.host()).isEqualTo("::1");
        assertThat(ipv6.port()).isEqualTo(7080);
        assertThat(ipv6.tls()).isFalse();
        assertThat(settings("wss://connect.example.dev/v1/connect").tls()).isTrue();
    }

    @Test
    void aBuiltClientIsANettyClientThatStartsNothingUntilStarted() {
        try (JBurrowClient client = JBurrowClient.builder()
                .server(URI.create("ws://localhost:7080/v1/connect"))
                .authtoken(TOKEN)
                .tunnel(TUNNEL)
                .build()) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void aServerIsRequired() {
        assertThatThrownBy(() ->
                        JBurrowClient.builder().authtoken(TOKEN).tunnel(TUNNEL).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server URL is required");
    }

    @Test
    void anAuthtokenIsRequired() {
        assertThatThrownBy(() -> JBurrowClient.builder()
                        .server(URI.create("wss://connect.example.dev/v1/connect"))
                        .tunnel(TUNNEL)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authtoken is required");
    }

    @Test
    void anAuthtokenOverTheProtocolsLimitIsRefusedWithoutQuotingIt() {
        String tooLong = "jb_" + "x".repeat(254);

        assertThatThrownBy(() -> JBurrowClient.builder()
                        .server(URI.create("wss://connect.example.dev/v1/connect"))
                        .authtoken(tooLong)
                        .tunnel(TUNNEL)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authtoken")
                .message()
                .doesNotContain("xxx");
    }

    @Test
    void exactlyOneTunnelIsRequired() {
        JBurrowClient.Builder none = JBurrowClient.builder()
                .server(URI.create("wss://connect.example.dev/v1/connect"))
                .authtoken(TOKEN);
        assertThatThrownBy(none::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Exactly one tunnel");

        JBurrowClient.Builder two = JBurrowClient.builder()
                .server(URI.create("wss://connect.example.dev/v1/connect"))
                .authtoken(TOKEN)
                .tunnel(TUNNEL)
                .tunnel(TunnelSpec.tcp("second", LocalTarget.of("localhost", 23)));
        assertThatThrownBy(two::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Exactly one tunnel");
    }

    @Test
    void anHttpTunnelCarriesItsSubdomainAndHostHeader() {
        ClientSettings settings = settingsOf(http().subdomain("myapp").hostHeader(HostHeader.REWRITE));

        assertThat(settings.tunnels())
                .containsExactly(TunnelSpec.http("default", LocalTarget.of("localhost", 3000))
                        .subdomain(new SubdomainName("myapp"))
                        .hostHeader(HostHeader.REWRITE));
        assertThat(settings.tunnels().getFirst().kind()).isEqualTo(TunnelKind.HTTP);
    }

    @Test
    void anHttpTunnelWithoutOptionsLetsTheServerChooseAndKeepsTheHost() {
        TunnelSpec tunnel = settingsOf(http()).tunnels().getFirst();

        assertThat(tunnel.subdomain()).isEmpty();
        assertThat(tunnel.hostHeader()).isEqualTo(HostHeader.KEEP);
    }

    @ParameterizedTest(name = "subdomain {0} is refused")
    @ValueSource(strings = {"ab", "MyApp", "-app", "app-", "my--app", "my.app", "my_app"})
    void aSubdomainFollowsTheNameRule(String name) {
        assertThatThrownBy(() -> settingsOf(http().subdomain(name)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subdomain must be")
                .hasMessageNotContaining(name);
    }

    @Test
    void httpOptionsNeedAnHttpTunnel() {
        assertThatThrownBy(() -> settingsOf(JBurrowClient.builder()
                        .server(URI.create("wss://connect.example.dev/v1/connect"))
                        .authtoken(TOKEN)
                        .tunnel(TUNNEL)
                        .subdomain("myapp")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs an HTTP tunnel");
    }

    @Test
    void httpAndAnotherTunnelAreMutuallyExclusive() {
        assertThatThrownBy(() -> settingsOf(http().tunnel(TUNNEL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Exactly one tunnel");
    }

    @Test
    void theClientOffersHttpWhateverItsTunnel() {
        assertThat(settings("wss://connect.example.dev/v1/connect").capabilities())
                .containsExactly(Capability.HTTP);
    }

    @Test
    void aClientStandingInForAnOlderOneOffersNoCapabilities() {
        ClientSettings settings = settingsOf(JBurrowClient.builder()
                .server(URI.create("wss://connect.example.dev/v1/connect"))
                .authtoken(TOKEN)
                .tunnel(TUNNEL)
                .capabilities(Set.of()));

        assertThat(settings.capabilities()).isEmpty();
    }

    @Test
    void anHttpTunnelNeedsHttpOffered() {
        assertThatThrownBy(() -> settingsOf(http().capabilities(Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A tunnel of kind http needs the http capability offered");
    }

    @Test
    void theClientIdentityFollowsTheProtocolsRules() {
        assertThatThrownBy(() -> JBurrowClient.builder()
                        .server(URI.create("wss://connect.example.dev/v1/connect"))
                        .authtoken(TOKEN)
                        .tunnel(TUNNEL)
                        .clientInfo("jburrow cli", "1.0")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void theHandshakeTimeoutIsPositiveAndBounded() {
        JBurrowClient.Builder builder = JBurrowClient.builder()
                .server(URI.create("wss://connect.example.dev/v1/connect"))
                .authtoken(TOKEN)
                .tunnel(TUNNEL);

        assertThatThrownBy(() -> builder.handshakeTimeout(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.handshakeTimeout(Duration.ofHours(1)).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theUserAgentNamesTheClientAndItsPlatform() {
        ClientSettings settings = new ClientSettings(
                URI.create("wss://connect.example.dev/v1/connect"),
                TOKEN,
                List.of(TUNNEL),
                new TunnelEvents() {},
                ClientSettings.clientInfo("jburrow-cli", "0.2.0"),
                Optional.empty(),
                ClientSettings.DEFAULT_HANDSHAKE_TIMEOUT,
                ClientSettings.DEFAULT_WINDOWS);

        assertThat(settings.userAgent()).matches("jburrow-cli/0\\.2\\.0 \\([a-z0-9_]+; [a-z0-9_]+\\)");
        assertThat(settings.clientInfo().os()).matches("[a-z0-9_]{1,32}");
    }

    @Test
    void settingsNeverPrintTheAuthtoken() {
        assertThat(settings("wss://connect.example.dev/v1/connect").toString())
                .doesNotContain(TOKEN)
                .contains("authtoken=***");
    }

    @Test
    void theDefaultClientIsThisLibraryWithItsBuildVersion() {
        assertThat(ClientSettings.DEFAULT_CLIENT_NAME).isEqualTo("jburrow-client-core");
        assertThat(ClientSettings.defaultClientVersion())
                .matches("[A-Za-z0-9.+_-]{1,64}")
                .isNotEqualTo("unknown");
    }

    private static JBurrowClient.Builder http() {
        return JBurrowClient.builder()
                .server(URI.create("wss://connect.example.dev/v1/connect"))
                .authtoken(TOKEN)
                .http(LocalTarget.of("localhost", 3000));
    }

    /** What {@code build()} would validate, without starting a client's I/O thread. */
    private static ClientSettings settingsOf(JBurrowClient.Builder builder) {
        return ((DefaultBuilder) builder).settings();
    }

    private static ClientSettings settings(String url) {
        return new ClientSettings(
                URI.create(url),
                TOKEN,
                List.of(TUNNEL),
                new TunnelEvents() {},
                ClientSettings.clientInfo("jburrow-test", "1.0.0"),
                Optional.empty(),
                ClientSettings.DEFAULT_HANDSHAKE_TIMEOUT,
                ClientSettings.DEFAULT_WINDOWS);
    }
}
