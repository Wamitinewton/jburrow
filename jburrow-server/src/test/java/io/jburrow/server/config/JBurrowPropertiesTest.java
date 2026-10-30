package io.jburrow.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.jburrow.protocol.control.Windows;
import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class JBurrowPropertiesTest {

    private static final Map<String, String> DEFAULTS = Map.of(
            "jburrow.api.prefix", "/api/v1",
            "jburrow.mail.from-address", "no-reply@jburrow.localhost",
            "jburrow.mail.from-name", "JBurrow",
            "jburrow.edge.connect-host", "localhost",
            "jburrow.tcp.port-range", "21000-21099",
            "jburrow.tcp.public-host", "localhost",
            "jburrow.http.domain", "localhost");

    private static final String LOCAL_ENTRY = "local:4e17d8906601750429e1cb705e2457eb786a348254390c98f634325c899d825f";

    @Test
    void defaultsBind() {
        JBurrowProperties properties = bind(DEFAULTS);

        assertThat(properties.api().prefix()).isEqualTo("/api/v1");
        assertThat(properties.mail().fromAddress()).isEqualTo("no-reply@jburrow.localhost");
        assertThat(properties.mail().fromName()).isEqualTo("JBurrow");
        assertThat(properties.mail().sesConfigurationSet()).isNull();
        assertThat(properties.mail().smokeTestRecipient()).isNull();
        assertThat(properties.auth().staticTokens()).isEmpty();
    }

    @Test
    void edgeAndTcpDefaultsMatchTheArchitecture() {
        JBurrowProperties properties = bind(DEFAULTS);

        JBurrowProperties.Edge edge = properties.edge();
        assertThat(edge.port()).isEqualTo(7080);
        assertThat(edge.bindAddress()).isEqualTo("0.0.0.0");
        assertThat(edge.transport()).isEqualTo(EdgeTransport.AUTO);
        assertThat(edge.effectiveIoThreads())
                .isEqualTo(Math.max(2, Runtime.getRuntime().availableProcessors()));
        assertThat(edge.trustedProxies()).containsExactly("127.0.0.1/32", "::1/128");
        assertThat(edge.maxSessions()).isEqualTo(200);
        assertThat(edge.maxHandshaking()).isEqualTo(64);
        assertThat(edge.maxSessionsPerPrincipal()).isEqualTo(3);
        assertThat(edge.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(edge.handshakeTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(edge.pingInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(edge.idleTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(edge.sessionWatermarks()).isEqualTo(new JBurrowProperties.Watermarks(64 * 1024, 256 * 1024));
        assertThat(edge.windows()).isEqualTo(new Windows(256 * 1024, 512 * 1024));
        assertThat(edge.maxConcurrentStreams()).isEqualTo(1_000);
        assertThat(edge.maxStreams()).isEqualTo(4_000);
        JBurrowProperties.Tcp tcp = properties.tcp();
        assertThat(tcp.bindAddress()).isEqualTo("0.0.0.0");
        assertThat(tcp.reuseDelay()).isEqualTo(Duration.ofSeconds(60));
        assertThat(tcp.visitorReceiveBuffer()).isEqualTo(DataSize.ofKilobytes(32));
        assertThat(tcp.parsedPortRange()).contains(new PortRange(21_000, 21_099));
    }

    @Test
    void httpDefaultsMatchTheArchitecture() {
        JBurrowProperties.Http http = bind(DEFAULTS).http();

        assertThat(http.domain()).isEqualTo("localhost");
        assertThat(http.publicScheme()).isEqualTo("https");
        assertThat(http.publicPort()).isEqualTo(443);
        assertThat(http.reservedNames()).isEmpty();
        assertThat(http.nameHold()).isEqualTo(Duration.ofSeconds(60));
        assertThat(http.maxRequestBody()).isEqualTo(DataSize.ofMegabytes(100));
        assertThat(http.responseTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(http.keepAliveTimeout()).isEqualTo(Duration.ofSeconds(75));
        assertThat(http.socketBuffer()).isEqualTo(DataSize.ofKilobytes(16));
        assertThat(http.parkAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(http.maxParked()).isEqualTo(DataSize.ofMegabytes(16));
        assertThat(http.maxParkedPerSession()).isEqualTo(DataSize.ofMegabytes(2));
    }

    @Test
    void httpSettingsBindAsALocalServerUsesThem() {
        JBurrowProperties.Http http = bind(with(Map.of(
                        "jburrow.http.domain", "example.dev",
                        "jburrow.http.public-scheme", "http",
                        "jburrow.http.public-port", "7080",
                        "jburrow.http.reserved-names", "status-page,grafana",
                        "jburrow.http.name-hold", "0s",
                        "jburrow.http.socket-buffer", "0",
                        "jburrow.http.max-parked", "0",
                        "jburrow.http.max-parked-per-session", "0")))
                .http();

        assertThat(http.domain()).isEqualTo("example.dev");
        assertThat(http.publicScheme()).isEqualTo("http");
        assertThat(http.publicPort()).isEqualTo(7080);
        assertThat(http.reservedNames()).containsExactly("status-page", "grafana");
        assertThat(http.nameHold()).isZero();
        assertThat(http.socketBuffer()).isEqualTo(DataSize.ofBytes(0));
        assertThat(http.maxParked()).isEqualTo(DataSize.ofBytes(0));
    }

    @ParameterizedTest
    @CsvSource({
        "localhost, localhost, localhost",
        "example.dev, connect.example.dev, tcp.example.dev",
        "example.dev, CONNECT.example.dev, tcp.elsewhere.dev",
        "tunnels.example.dev, connect.example.dev, example.dev"
    })
    void serviceHostsOutsideTheDomainOrOneLabelUnderItBind(String domain, String connectHost, String tcpHost) {
        JBurrowProperties properties = bind(with(Map.of(
                "jburrow.http.domain", domain,
                "jburrow.edge.connect-host", connectHost,
                "jburrow.tcp.public-host", tcpHost)));

        assertThat(properties.http().domain()).isEqualTo(domain);
    }

    @Test
    void labelsUnderTheDomainAreFoundWithoutCase() {
        JBurrowProperties.Http http =
                bind(with(Map.of("jburrow.http.domain", "example.dev"))).http();

        assertThat(http.labelsUnderDomain("Connect.Example.dev")).contains("connect");
        assertThat(http.labelsUnderDomain("a.b.example.dev")).contains("a.b");
        assertThat(http.labelsUnderDomain("example.dev")).isEmpty();
        assertThat(http.labelsUnderDomain("notexample.dev")).isEmpty();
        assertThat(http.labelsUnderDomain("tcp.elsewhere.dev")).isEmpty();
    }

    @Test
    void transportBindsWithoutCaseAndIoThreadsCanBeSet() {
        JBurrowProperties properties =
                bind(with(Map.of("jburrow.edge.transport", "nio", "jburrow.edge.io-threads", "3")));

        assertThat(properties.edge().transport()).isEqualTo(EdgeTransport.NIO);
        assertThat(properties.edge().effectiveIoThreads()).isEqualTo(3);
    }

    @Test
    void trustedProxiesBindFromACommaSeparatedValue() {
        JBurrowProperties properties = bind(with(Map.of("jburrow.edge.trusted-proxies", "10.42.0.0/16,fd00::/8")));

        assertThat(properties.edge().trustedProxies()).containsExactly("10.42.0.0/16", "fd00::/8");
    }

    /** A value that was never set has no origin, so the binder names its field path rather than the key. */
    @ParameterizedTest
    @CsvSource({
        "jburrow.edge.connect-host, edge.connectHost",
        "jburrow.tcp.port-range, tcp.portRange",
        "jburrow.tcp.public-host, tcp.publicHost",
        "jburrow.http.domain, http.domain"
    })
    void aMissingRequiredSettingFailsNamingItsField(String key, String field) {
        Map<String, String> values = new HashMap<>(DEFAULTS);
        values.remove(key);

        assertFailsNaming(values, field);
    }

    static Stream<Arguments> crossFieldViolations() {
        return Stream.of(
                arguments(
                        Map.of("jburrow.edge.ping-interval", "20s", "jburrow.edge.idle-timeout", "39s"),
                        "jburrow.edge.idle-timeout"),
                arguments(Map.of("jburrow.edge.ping-interval", "500ms"), "jburrow.edge.ping-interval"),
                arguments(Map.of("jburrow.edge.request-timeout", "0s"), "jburrow.edge.request-timeout"),
                arguments(Map.of("jburrow.edge.handshake-timeout", "-1s"), "jburrow.edge.handshake-timeout"),
                arguments(
                        Map.of("jburrow.edge.session-write-buffer", "512KB-128KB"),
                        "jburrow.edge.session-write-buffer"),
                arguments(
                        Map.of("jburrow.edge.session-write-buffer", "128KB-128KB"),
                        "jburrow.edge.session-write-buffer"),
                arguments(Map.of("jburrow.edge.session-write-buffer", "128KB"), "jburrow.edge.session-write-buffer"),
                arguments(Map.of("jburrow.edge.stream-window", "65535B"), "jburrow.edge.stream-window"),
                arguments(Map.of("jburrow.edge.stream-window", "2GB"), "jburrow.edge.stream-window"),
                arguments(
                        Map.of("jburrow.edge.stream-window", "1MB", "jburrow.edge.session-window", "512KB"),
                        "jburrow.edge.session-window"),
                arguments(Map.of("jburrow.edge.session-window", "2GB"), "jburrow.edge.session-window"),
                arguments(Map.of("jburrow.edge.max-concurrent-streams", "0"), "edge.maxConcurrentStreams"),
                arguments(Map.of("jburrow.edge.max-streams", "0"), "edge.maxStreams"),
                arguments(
                        Map.of("jburrow.edge.max-concurrent-streams", "1000", "jburrow.edge.max-streams", "999"),
                        "jburrow.edge.max-streams"),
                arguments(
                        Map.of("jburrow.edge.max-sessions", "10", "jburrow.edge.max-handshaking", "11"),
                        "jburrow.edge.max-handshaking"),
                arguments(Map.of("jburrow.tcp.port-range", "80-1100"), "jburrow.tcp.port-range"),
                arguments(Map.of("jburrow.tcp.port-range", "21099-21000"), "jburrow.tcp.port-range"),
                arguments(Map.of("jburrow.tcp.port-range", "21000"), "jburrow.tcp.port-range"),
                arguments(Map.of("jburrow.tcp.port-range", "7000-7999"), "jburrow.tcp.port-range"),
                arguments(Map.of("jburrow.edge.port", "21050"), "jburrow.tcp.port-range"),
                arguments(Map.of("jburrow.tcp.reuse-delay", "-1s"), "jburrow.tcp.reuse-delay"),
                arguments(Map.of("jburrow.tcp.visitor-receive-buffer", "1KB"), "jburrow.tcp.visitor-receive-buffer"),
                arguments(Map.of("jburrow.tcp.visitor-receive-buffer", "8MB"), "jburrow.tcp.visitor-receive-buffer"),
                arguments(Map.of("jburrow.http.domain", "Example.dev"), "http.domain"),
                arguments(Map.of("jburrow.http.domain", "dev"), "http.domain"),
                arguments(Map.of("jburrow.http.domain", "-bad.example.dev"), "http.domain"),
                arguments(Map.of("jburrow.http.domain", "example.dev."), "http.domain"),
                arguments(Map.of("jburrow.http.public-scheme", "ftp"), "http.publicScheme"),
                arguments(Map.of("jburrow.http.public-port", "0"), "http.publicPort"),
                arguments(Map.of("jburrow.http.public-port", "65536"), "http.publicPort"),
                arguments(Map.of("jburrow.http.reserved-names", "status,Grafana"), "jburrow.http.reserved-names"),
                arguments(Map.of("jburrow.http.reserved-names", "ab"), "jburrow.http.reserved-names"),
                arguments(Map.of("jburrow.http.reserved-names", "two--hyphens"), "jburrow.http.reserved-names"),
                arguments(Map.of("jburrow.http.name-hold", "-1s"), "jburrow.http.name-hold"),
                arguments(Map.of("jburrow.http.max-request-body", "0B"), "jburrow.http.max-request-body"),
                arguments(Map.of("jburrow.http.response-timeout", "0s"), "jburrow.http.response-timeout"),
                arguments(Map.of("jburrow.http.keep-alive-timeout", "-1s"), "jburrow.http.keep-alive-timeout"),
                arguments(Map.of("jburrow.http.socket-buffer", "1KB"), "jburrow.http.socket-buffer"),
                arguments(Map.of("jburrow.http.socket-buffer", "8MB"), "jburrow.http.socket-buffer"),
                arguments(Map.of("jburrow.http.park-after", "0s"), "jburrow.http.park-after"),
                arguments(
                        Map.of("jburrow.http.max-parked", "1MB", "jburrow.http.max-parked-per-session", "2MB"),
                        "jburrow.http.max-parked-per-session"),
                arguments(Map.of("jburrow.edge.connect-host", "connect.edge.localhost"), "jburrow.edge.connect-host"),
                arguments(Map.of("jburrow.tcp.public-host", "a.tcp.localhost"), "jburrow.tcp.public-host"));
    }

    @ParameterizedTest
    @MethodSource("crossFieldViolations")
    void eachRuleFailsNamingItsKey(Map<String, String> overrides, String key) {
        assertFailsNaming(with(overrides), key);
    }

    @Test
    void anIdleTimeoutOfExactlyTwicePingIntervalIsAllowed() {
        JBurrowProperties properties =
                bind(with(Map.of("jburrow.edge.ping-interval", "20s", "jburrow.edge.idle-timeout", "40s")));

        assertThat(properties.edge().idleTimeout()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void windowsAtTheirBoundsBind() {
        JBurrowProperties properties = bind(with(Map.of(
                "jburrow.edge.stream-window", "64KB",
                "jburrow.edge.session-window", "2147483647B",
                "jburrow.edge.max-concurrent-streams", "7",
                "jburrow.edge.max-streams", "7")));

        assertThat(properties.edge().windows()).isEqualTo(new Windows(65_536, Integer.MAX_VALUE));
        assertThat(properties.edge().maxStreams()).isEqualTo(7);
    }

    @Test
    void aVisitorReceiveBufferOfZeroLeavesItToTheKernel() {
        JBurrowProperties properties = bind(with(Map.of("jburrow.tcp.visitor-receive-buffer", "0")));

        assertThat(properties.tcp().visitorReceiveBuffer().toBytes()).isZero();
    }

    @Test
    void theSessionWindowMayEqualTheStreamWindow() {
        JBurrowProperties properties =
                bind(with(Map.of("jburrow.edge.stream-window", "1MB", "jburrow.edge.session-window", "1MB")));

        assertThat(properties.edge().windows()).isEqualTo(new Windows(1_048_576, 1_048_576));
    }

    /** The binder ignores unknown keys, so a stale value in an old environment is harmless but does nothing. */
    @Test
    void theRemovedVisitorWriteBufferIsNotBound() {
        JBurrowProperties properties = bind(with(Map.of("jburrow.edge.visitor-write-buffer", "32KB-128KB")));

        assertThat(properties.edge()).isNotNull();
        assertThat(JBurrowProperties.Edge.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain("visitorWriteBuffer");
    }

    @Test
    void anEdgeOnPortZeroIsAllowed() {
        assertThat(bind(with(Map.of("jburrow.edge.port", "0"))).edge().port()).isZero();
    }

    @Test
    void emptyOptionalMailSettingsBind() {
        JBurrowProperties properties =
                bind(with(Map.of("jburrow.mail.ses-configuration-set", "", "jburrow.mail.smoke-test-recipient", "")));

        assertThat(properties.mail().smokeTestRecipient()).isEmpty();
    }

    @Test
    void blankFromAddressFailsNamingTheKey() {
        assertThatThrownBy(() -> bind(with(Map.of("jburrow.mail.from-address", " "))))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("jburrow.mail.from-address");
    }

    @Test
    void invalidSmokeTestRecipientFailsNamingTheKey() {
        assertThatThrownBy(() -> bind(with(Map.of("jburrow.mail.smoke-test-recipient", "not-an-address"))))
                .rootCause()
                .hasMessageContaining("jburrow.mail.smoke-test-recipient");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api/v1", "/api/v1/", "/", ""})
    void malformedPrefixFailsNamingTheKey(String prefix) {
        assertThatThrownBy(() -> bind(with(Map.of("jburrow.api.prefix", prefix))))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("jburrow.api.prefix");
    }

    @Test
    void staticTokensBindFromACommaSeparatedValue() {
        JBurrowProperties properties =
                bind(with(Map.of("jburrow.auth.static-tokens", LOCAL_ENTRY + "," + "alice:" + "0".repeat(64))));

        assertThat(properties.auth().staticTokens()).containsExactly(LOCAL_ENTRY, "alice:" + "0".repeat(64));
    }

    @Test
    void staticTokensBindFromAnIndexedList() {
        JBurrowProperties properties = bind(with(Map.of("jburrow.auth.static-tokens[0]", LOCAL_ENTRY)));

        assertThat(properties.auth().staticTokens()).containsExactly(LOCAL_ENTRY);
    }

    @Test
    void staticTokensAreTrimmedAroundCommas() {
        JBurrowProperties properties =
                bind(with(Map.of("jburrow.auth.static-tokens", LOCAL_ENTRY + " , " + LOCAL_ENTRY)));

        assertThat(properties.auth().staticTokens()).containsExactly(LOCAL_ENTRY, LOCAL_ENTRY);
    }

    @Test
    void anEmptyStaticTokenListBinds() {
        JBurrowProperties properties = bind(with(Map.of("jburrow.auth.static-tokens", "")));

        assertThat(properties.auth().staticTokens()).isEmpty();
    }

    private static void assertFailsNaming(Map<String, String> values, String key) {
        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining(key);
    }

    private static Map<String, String> with(Map<String, String> overrides) {
        Map<String, String> values = new HashMap<>(DEFAULTS);
        values.putAll(overrides);
        return values;
    }

    private static JBurrowProperties bind(Map<String, String> values) {
        try (LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            return new Binder(new MapConfigurationPropertySource(values))
                    .bindOrCreate(
                            "jburrow", Bindable.of(JBurrowProperties.class), new ValidationBindHandler(validator));
        }
    }
}
