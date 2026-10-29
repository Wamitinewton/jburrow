package io.jburrow.server.e2e;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.protocol.adapter.netty.Frame;
import io.jburrow.protocol.control.ErrorBody;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.Windows;
import io.jburrow.protocol.flow.FlowLimits;
import io.jburrow.protocol.testsupport.ScriptedPeer;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.FrameLimits;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.TestPropertySource;

/**
 * Every series of {@code docs/phase-1-tcp-tunnel/ARCHITECTURE.md} §Observability, read from the Prometheus scrape
 * before and after the {@link ObservedScenario}: it exists with its labels and moved by what the scenario did. The
 * series {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Observability adds are proven by scenarios of their own,
 * the flow-control ones in {@link FlowControl}.
 *
 * <p>The server takes two streams in all, so a third visitor finds it full.
 */
@TestPropertySource(
        properties = {
            "jburrow.tcp.port-range=31400-31499",
            "jburrow.edge.max-concurrent-streams=1",
            "jburrow.edge.max-streams=2",
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
        assertMoved(0, "jburrow_visitor_connections_refused_total", "reason", "server_stream_limit");
    }

    @Test
    void aVisitorBeyondTheServersStreamCapIsRefused() throws Exception {
        LocalService echo = closeAfterTest(LocalService.echo());
        List<Tunnel> tunnels = List.of(
                openTunnel(ALICE_TOKEN, tcp(echo.port())),
                openTunnel(BOB_TOKEN, tcp(echo.port())),
                openTunnel(ALICE_TOKEN, tcp(echo.port())));
        Scrape before = Scrape.parse(scrape());

        byte[] message = "hello".getBytes(US_ASCII);
        for (Tunnel tunnel : tunnels.subList(0, 2)) {
            assertThat(roundTrip(visit(tunnel.port()), message)).isEqualTo(message);
        }
        try (Socket refused = visit(tunnels.get(2).port())) {
            assertThat(refused.getInputStream().read()).isEqualTo(-1);
        }

        Scrape after = Scrape.parse(scrape());
        assertThat(moved(before, after, "jburrow_visitor_connections_refused_total", "reason", "server_stream_limit"))
                .isOne();
        assertThat(moved(before, after, "jburrow_visitor_connections_refused_total", "reason", "stream_limit"))
                .isZero();
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

        assertMoved(0, "jburrow_protocol_errors_total", "code", "FLOW_CONTROL_ERROR", "side", "local");
        assertThat(after.value("jburrow_backpressure_pauses_total", "direction", "inbound"))
                .isNotNegative();
        assertMoved(0, "jburrow_backpressure_pauses_total", "direction", "outbound");
        for (String scope : List.of("stream", "session")) {
            assertThat(after.value("jburrow_flow_blocked_total", "scope", scope))
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
        return moved(before, after, name, labels);
    }

    static double moved(Scrape before, Scrape after, String name, String... labels) {
        return after.value(name, labels) - before.value(name, labels);
    }

    /**
     * The series of flow control, each moved by a scenario of its own on a server with phase 2's stream limits, read
     * from the scrape before and after it. Outbound backpressure, which phase 2 no longer emits, stays at 0 throughout.
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @TestPropertySource(properties = "jburrow.tcp.port-range=31950-31999")
    @Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @ExtendWith(FlowStateDump.class)
    class FlowControl extends TunnelTestSupport {

        private static final long STALLED_UPLOAD = 4L * 1024 * 1024;
        private static final int THROTTLED_VISITORS = 20;
        private static final long THROTTLED_EACH = 256 * 1024;
        private static final long BYTES_PER_SECOND = 2 * 1024 * 1024;
        /** Far more than the server's windows and the visitor socket's buffers together. */
        private static final long FLOOD = 16L * 1024 * 1024;

        @Test
        void aVisitorWhoseLocalServiceDoesNotReadWaitsForItsStreamsCredit() throws Exception {
            LocalService stalled = closeAfterTest(LocalService.stalled());
            Tunnel tunnel = openTunnel(stalled);
            Scrape before = Scrape.parse(scrape());

            Socket visitor = visit(tunnel.port());
            async(() -> send(visitor.getOutputStream(), STALLED_UPLOAD, 1));
            await(
                    "the visitor waits for its stream's credit",
                    () -> counter("jburrow.flow.blocked", "scope", "stream")
                            > before.value("jburrow_flow_blocked_total", "scope", "stream"));

            Scrape after = Scrape.parse(scrape());
            assertThat(moved(before, after, "jburrow_flow_blocked_total", "scope", "stream"))
                    .isPositive();
            assertNoOutboundPauses(before, after);
        }

        @Test
        void visitorsOfAThrottledClientWaitForTheSessionsCredit() throws Exception {
            Map<Long, Received> received = new ConcurrentHashMap<>();
            LocalService sink = closeAfterTest(LocalService.sink(received, new AtomicLong()));
            ThrottlingProxy proxy = closeAfterTest(ThrottlingProxy.start(edge.boundPort(), BYTES_PER_SECOND));
            Windows smallest = new Windows(FlowLimits.MIN_STREAM_WINDOW, FlowLimits.MIN_STREAM_WINDOW);
            Tunnel tunnel = openTunnel(client(ALICE_TOKEN, tcp(sink.port()))
                    .server(connectUriThrough(proxy))
                    .windows(smallest));
            Scrape before = Scrape.parse(scrape());

            List<CompletableFuture<String>> uploads = new ArrayList<>();
            for (int i = 0; i < THROTTLED_VISITORS; i++) {
                Socket visitor = visit(tunnel.port());
                long id = i;
                uploads.add(async(() -> {
                    LocalService.writeId(visitor.getOutputStream(), id);
                    String digest = send(visitor.getOutputStream(), THROTTLED_EACH, id);
                    visitor.shutdownOutput();
                    return digest;
                }));
            }
            List<String> sent = awaitAll(uploads, Duration.ofMinutes(1));
            await("the sink has every upload", () -> received.size() == THROTTLED_VISITORS);

            Scrape after = Scrape.parse(scrape());
            for (int i = 0; i < THROTTLED_VISITORS; i++) {
                assertThat(received.get((long) i)).isEqualTo(new Received(THROTTLED_EACH, sent.get(i)));
            }
            assertThat(moved(before, after, "jburrow_flow_blocked_total", "scope", "session"))
                    .isPositive();
            assertNoOutboundPauses(before, after);
        }

        @Test
        void windowUpdatesMoveBothWaysDuringATransfer() throws Exception {
            LocalService echo = closeAfterTest(LocalService.echo());
            Tunnel tunnel = openTunnel(echo);
            Scrape before = Scrape.parse(scrape());

            Socket visitor = visit(tunnel.port());
            long length = 2L * 1024 * 1024;
            CompletableFuture<String> sent = async(() -> {
                String digest = send(visitor.getOutputStream(), length, 2);
                visitor.shutdownOutput();
                return digest;
            });
            assertThat(drain(visitor.getInputStream()))
                    .isEqualTo(new Received(length, sent.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)));

            Scrape after = Scrape.parse(scrape());
            for (String direction : List.of("received", "sent")) {
                assertThat(moved(
                                before, after, "jburrow_frames_total", "type", "window_update", "direction", direction))
                        .as("WINDOW_UPDATE %s", direction)
                        .isPositive();
            }
            assertNoOutboundPauses(before, after);
        }

        @Test
        void aClientThatOverrunsAWindowIsAFlowControlError() throws Exception {
            Scrape before = Scrape.parse(scrape());
            ScriptedPeer client = scriptedClient();
            RegisteredTunnel registered = establish(client);
            client.ignoreWindowUpdates();
            visit(registered.publicPort());
            int streamId = client.expect(Frame.Open.class, TIMEOUT).streamId();

            flood(client, streamId);
            assertThat(nextError(client).code()).isEqualTo(ErrorCode.FLOW_CONTROL_ERROR);

            await("the session has ended", () -> gauge("jburrow.sessions.active") == 0);
            Scrape after = Scrape.parse(scrape());
            assertThat(moved(
                            before,
                            after,
                            "jburrow_protocol_errors_total",
                            "code",
                            "FLOW_CONTROL_ERROR",
                            "side",
                            "local"))
                    .isOne();
            assertNoOutboundPauses(before, after);
        }

        /** Sends {@code DATA} on {@code streamId} regardless of credit, to a visitor that never reads. */
        private static void flood(ScriptedPeer client, int streamId) {
            try {
                for (long sent = 0; sent < FLOOD; sent += FrameLimits.MAX_DATA_PAYLOAD) {
                    client.send(
                            new Frame.Data(streamId, Unpooled.wrappedBuffer(new byte[FrameLimits.MAX_DATA_PAYLOAD])));
                }
            } catch (IllegalStateException closedByTheServer) {
                return;
            }
        }

        /** The {@code ERROR} that ends the session, after the resets of its streams. */
        private static ErrorBody nextError(ScriptedPeer client) {
            while (true) {
                Frame frame = client.expect(Frame.class, TIMEOUT);
                if (frame instanceof Frame.Error error) {
                    return error.body();
                }
                assertThat(frame).isInstanceOf(Frame.Close.class);
                ReferenceCountUtil.release(frame);
            }
        }

        private static void assertNoOutboundPauses(Scrape before, Scrape after) {
            assertThat(moved(before, after, "jburrow_backpressure_pauses_total", "direction", "outbound"))
                    .as("outbound pauses, which phase 2 no longer makes")
                    .isZero();
        }
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
