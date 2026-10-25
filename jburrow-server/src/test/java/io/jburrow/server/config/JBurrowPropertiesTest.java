package io.jburrow.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.jburrow.server.core.portpool.domain.PortRange;
import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
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
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class JBurrowPropertiesTest {

    private static final Map<String, String> DEFAULTS = Map.of(
            "jburrow.api.prefix", "/api/v1",
            "jburrow.mail.from-address", "no-reply@jburrow.localhost",
            "jburrow.mail.from-name", "JBurrow",
            "jburrow.edge.connect-host", "localhost",
            "jburrow.tcp.port-range", "21000-21099",
            "jburrow.tcp.public-host", "localhost");

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
        assertThat(edge.sessionWatermarks()).isEqualTo(new JBurrowProperties.Watermarks(128 * 1024, 512 * 1024));
        assertThat(edge.visitorWatermarks()).isEqualTo(new JBurrowProperties.Watermarks(32 * 1024, 128 * 1024));
        JBurrowProperties.Tcp tcp = properties.tcp();
        assertThat(tcp.bindAddress()).isEqualTo("0.0.0.0");
        assertThat(tcp.reuseDelay()).isEqualTo(Duration.ofSeconds(60));
        assertThat(tcp.parsedPortRange()).contains(new PortRange(21_000, 21_099));
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
        "jburrow.tcp.public-host, tcp.publicHost"
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
                arguments(
                        Map.of("jburrow.edge.visitor-write-buffer", "lots-more"), "jburrow.edge.visitor-write-buffer"),
                arguments(Map.of("jburrow.edge.visitor-write-buffer", "0B-1KB"), "jburrow.edge.visitor-write-buffer"),
                arguments(
                        Map.of("jburrow.edge.max-sessions", "10", "jburrow.edge.max-handshaking", "11"),
                        "jburrow.edge.max-handshaking"),
                arguments(Map.of("jburrow.tcp.port-range", "80-1100"), "jburrow.tcp.port-range"),
                arguments(Map.of("jburrow.tcp.port-range", "21099-21000"), "jburrow.tcp.port-range"),
                arguments(Map.of("jburrow.tcp.port-range", "21000"), "jburrow.tcp.port-range"),
                arguments(Map.of("jburrow.tcp.port-range", "7000-7999"), "jburrow.tcp.port-range"),
                arguments(Map.of("jburrow.edge.port", "21050"), "jburrow.tcp.port-range"),
                arguments(Map.of("jburrow.tcp.reuse-delay", "-1s"), "jburrow.tcp.reuse-delay"));
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
