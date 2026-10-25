package io.jburrow.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.TestPropertySource;

/**
 * Every series of {@code docs/phase-1-tcp-tunnel/ARCHITECTURE.md} §Observability, read from the Prometheus scrape
 * before and after the {@link ObservedScenario}: it exists with its labels and moved by what the scenario did.
 */
@TestPropertySource(
        properties = {
            "jburrow.tcp.port-range=41400-41499",
            "jburrow.edge.ping-interval=1s",
            "jburrow.edge.idle-timeout=2s"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TunnelMetricsIntegrationTest extends TunnelTestSupport {

    private static final int PORT_RANGE_SIZE = 100;

    private static final Pattern UUID =
            Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern PORT = Pattern.compile("\\d{1,5}");

    private ObservedScenario scenario;
    private Scrape before;
    private Scrape after;

    @BeforeAll
    void runTheScenario() throws Exception {
        before = Scrape.parse(scrape());
        scenario = ObservedScenario.run(this);
        after = Scrape.parse(scrape());
    }

    @Test
    void sessionsAndHandshakes() {
        assertThat(after.value("jburrow_sessions_active")).isEqualTo(before.value("jburrow_sessions_active"));

        assertMoved(2, "jburrow_session_handshakes_total", "result", "established");
        assertMoved(1, "jburrow_session_handshakes_total", "result", "AUTHTOKEN_INVALID");
        assertMoved(1, "jburrow_session_handshakes_total", "result", "UNSUPPORTED_PROTOCOL_VERSION");

        assertMoved(1, "jburrow_sessions_closed_total", "reason", "client_closed");
        assertMoved(1, "jburrow_sessions_closed_total", "reason", "AUTHTOKEN_INVALID");
        assertMoved(1, "jburrow_sessions_closed_total", "reason", "HEARTBEAT_TIMEOUT");
        assertMoved(0, "jburrow_sessions_closed_total", "reason", "connection_lost");

        assertMoved(2, "jburrow_register_duration_seconds_count", "result", "established");
        assertMoved(1, "jburrow_register_duration_seconds_count", "result", "AUTHTOKEN_INVALID");
        assertThat(after.value("jburrow_register_duration_seconds_bucket", "result", "established", "le", "+Inf"))
                .isEqualTo(after.value("jburrow_register_duration_seconds_count", "result", "established"));
    }

    @Test
    void streamsAndVisitors() {
        assertMoved(ObservedScenario.STREAMS, "jburrow_streams_opened_total", "kind", "tcp");
        assertThat(after.value("jburrow_streams_active", "kind", "tcp")).isZero();

        assertMoved(1, "jburrow_stream_resets_total", "code", "PEER_RESET", "side", "remote");
        assertMoved(0, "jburrow_stream_resets_total", "code", "CANCELLED", "side", "local");

        assertMoved(1, "jburrow_visitor_connections_refused_total", "reason", "stream_limit");
        assertMoved(0, "jburrow_visitor_connections_refused_total", "reason", "not_established");
    }

    @Test
    void bytesAndFrames() {
        assertMoved(
                scenario.echoedBytes() + scenario.resetStreamBytes(),
                "jburrow_tunnel_bytes_total",
                "kind",
                "tcp",
                "direction",
                "inbound");
        assertMoved(scenario.echoedBytes(), "jburrow_tunnel_bytes_total", "kind", "tcp", "direction", "outbound");

        assertMoved(3, "jburrow_frames_total", "type", "hello", "direction", "received");
        assertMoved(3, "jburrow_frames_total", "type", "hello", "direction", "sent");
        assertMoved(3, "jburrow_frames_total", "type", "register", "direction", "received");
        assertMoved(2, "jburrow_frames_total", "type", "register", "direction", "sent");
        assertMoved(ObservedScenario.STREAMS, "jburrow_frames_total", "type", "open", "direction", "sent");
        assertMoved(2, "jburrow_frames_total", "type", "error", "direction", "sent");
        assertMoved(2, "jburrow_frames_total", "type", "close", "direction", "received");
        assertMoved(1, "jburrow_frames_total", "type", "close", "direction", "sent");
        assertThat(moved("jburrow_frames_total", "type", "data", "direction", "received"))
                .isPositive();
        assertThat(moved("jburrow_frames_total", "type", "data", "direction", "sent"))
                .isPositive();
        assertThat(moved("jburrow_frames_total", "type", "ping", "direction", "sent"))
                .as("the server pinged the silent client")
                .isPositive();
        assertMoved(0, "jburrow_frames_total", "type", "goaway", "direction", "received");
    }

    @Test
    void protocolErrorsBackpressurePortsAndMemory() {
        assertMoved(1, "jburrow_protocol_errors_total", "code", "AUTHTOKEN_INVALID", "side", "local");
        assertMoved(1, "jburrow_protocol_errors_total", "code", "HEARTBEAT_TIMEOUT", "side", "local");
        assertMoved(0, "jburrow_protocol_errors_total", "code", "PROTOCOL_ERROR", "side", "remote");

        for (String direction : List.of("inbound", "outbound")) {
            assertThat(after.value("jburrow_backpressure_pauses_total", "direction", direction))
                    .isNotNegative();
        }

        double reserved = after.value("jburrow_tcp_ports", "state", "reserved");
        assertThat(reserved).isEqualTo(before.value("jburrow_tcp_ports", "state", "reserved"));
        assertThat(after.value("jburrow_tcp_ports", "state", "free")
                        + reserved
                        + after.value("jburrow_tcp_ports", "state", "quarantined"))
                .isEqualTo(PORT_RANGE_SIZE);

        assertThat(after.value("jburrow_netty_direct_memory_bytes")).isNotNegative();
    }

    /**
     * An unbounded label would show up as a value that looks like a UUID, an IP address or a port. The {@code instance}
     * tag is the exception: one UUID per process, on every series by design.
     */
    @Test
    void noSeriesIsLabelledWithAnIdentifier() {
        List<String> series = after.names().stream()
                .filter(name -> name.startsWith("jburrow_"))
                .toList();
        assertThat(series).isNotEmpty();
        for (String key : after.keysStartingWith("jburrow_")) {
            Scrape.labels(key).forEach((label, value) -> {
                if (label.equals("instance")) {
                    return;
                }
                assertThat(value)
                        .as("label %s of %s", label, key)
                        .doesNotMatch(UUID)
                        .doesNotMatch(IPV4)
                        .doesNotMatch(PORT)
                        .doesNotContain(":")
                        .isNotEqualTo(scenario.sessionId())
                        .isNotEqualTo("alice");
            });
        }
    }

    private void assertMoved(double expected, String name, String... labels) {
        assertThat(moved(name, labels))
                .as("change in %s %s", name, List.of(labels))
                .isEqualTo(expected);
    }

    private double moved(String name, String... labels) {
        return after.value(name, labels) - before.value(name, labels);
    }

    /** One Prometheus scrape: every sample by its series key, {@code name{label="value",...}}. */
    record Scrape(Map<String, Double> samples) {

        private static final Pattern LABEL = Pattern.compile("(\\w+)=\"((?:[^\"\\\\]|\\\\.)*)\"");

        static Scrape parse(String text) {
            Map<String, Double> samples = new LinkedHashMap<>();
            for (String line : text.lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int space = line.lastIndexOf(' ');
                samples.put(line.substring(0, space), Double.parseDouble(line.substring(space + 1)));
            }
            return new Scrape(samples);
        }

        static Map<String, String> labels(String key) {
            Map<String, String> labels = new LinkedHashMap<>();
            int brace = key.indexOf('{');
            if (brace >= 0) {
                Matcher matcher = LABEL.matcher(key.substring(brace));
                while (matcher.find()) {
                    labels.put(matcher.group(1), matcher.group(2));
                }
            }
            return labels;
        }

        static String name(String key) {
            int brace = key.indexOf('{');
            return brace < 0 ? key : key.substring(0, brace);
        }

        List<String> names() {
            return samples.keySet().stream().map(Scrape::name).distinct().toList();
        }

        List<String> keysStartingWith(String prefix) {
            return samples.keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .toList();
        }

        /** The one sample of {@code name} carrying every {@code label, value} pair given; it must exist. */
        double value(String name, String... labels) {
            List<Double> matches = samples.entrySet().stream()
                    .filter(sample -> name(sample.getKey()).equals(name) && hasLabels(labels(sample.getKey()), labels))
                    .map(Map.Entry::getValue)
                    .toList();
            assertThat(matches).as("samples of %s %s", name, List.of(labels)).hasSize(1);
            return matches.getFirst();
        }

        private static boolean hasLabels(Map<String, String> actual, String... expected) {
            for (int i = 0; i < expected.length; i += 2) {
                if (!expected[i + 1].equals(actual.get(expected[i]))) {
                    return false;
                }
            }
            return true;
        }
    }
}
