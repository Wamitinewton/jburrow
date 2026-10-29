package io.jburrow.server.e2e;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InterruptedIOException;
import java.net.Socket;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * The log of the {@link ObservedScenario}: every line {@code docs/phase-1-tcp-tunnel/ARCHITECTURE.md} §Observability
 * promises, with its fields and those {@code docs/phase-2-multiplexing/ARCHITECTURE.md} §Observability adds, and
 * nothing that must never be logged.
 */
@TestPropertySource(
        properties = {
            "jburrow.tcp.port-range=31500-31599",
            "jburrow.edge.max-concurrent-streams=1",
            "jburrow.edge.ping-interval=1s",
            "jburrow.edge.idle-timeout=2s"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class LogHygieneIntegrationTest extends TunnelTestSupport {

    private static final Pattern BLOCKED_MS = Pattern.compile(" blockedMs=(\\d+) ");
    /** An echo of a few kilobytes never runs out of credit; a little over 0 allows for a scheduling hiccup. */
    private static final Duration UNBLOCKED_BOUND = Duration.ofMillis(100);

    private static final Duration UNDRAINED = Duration.ofSeconds(2);
    /** More than the client's stream window and the local connection's socket buffers can take. */
    private static final long HELD_BACK_UPLOAD = 8L * 1024 * 1024;

    private ObservedScenario scenario;
    /** The scenario's output. The extension starts a new capture for each test, so it is kept here. */
    private String log;

    @BeforeAll
    void runTheScenario(CapturedOutput output) throws Exception {
        scenario = ObservedScenario.run(this);
        await(
                "the established session's closing is logged",
                () -> output.getAll().contains("session closed: session=" + scenario.sessionId()));
        log = output.getAll();
    }

    @Test
    void theSessionIsLoggedWhenEstablishedAndWhenClosed() {
        assertThat(line("INFO", "session established: session=" + scenario.sessionId()))
                .contains(
                        "principal=alice",
                        "client=" + ObservedScenario.CLIENT_NAME + "/" + ObservedScenario.CLIENT_VERSION,
                        "address=127.0.0.1",
                        "tunnels=[tcp:" + scenario.tunnelPort() + "]");

        assertThat(line("INFO", "session closed: session=" + scenario.sessionId()))
                .contains(
                        "principal=alice",
                        "reason=client_closed",
                        "duration=",
                        "streams=" + ObservedScenario.STREAMS,
                        "maxStreams=1",
                        "bytesIn=" + (scenario.echoedBytes() + scenario.resetStreamBytes()),
                        "bytesOut=" + scenario.echoedBytes());
    }

    @Test
    void eachStreamIsLoggedWithItsBytesAndHowItEnded() {
        List<String> streams = lines("INFO", "stream closed: session=" + scenario.sessionId());

        assertThat(streams).hasSize(ObservedScenario.STREAMS);
        assertThat(streams.get(0))
                .contains(
                        "stream=1",
                        "visitor=127.0.0.1:",
                        "bytesIn=" + scenario.echoedBytes(),
                        "bytesOut=" + scenario.echoedBytes(),
                        "duration=",
                        "ending=\"closed\"");
        assertThat(blockedMillis(streams.get(0)))
                .as("time an echo stream waited for credit")
                .isLessThan(UNBLOCKED_BOUND.toMillis());
        assertThat(streams.get(1))
                .contains(
                        "stream=2", "bytesIn=" + scenario.resetStreamBytes(), "ending=\"reset by client PEER_RESET\"");
    }

    /** The client's local service reads nothing for {@link #UNDRAINED}, so the visitor waits for its stream's credit. */
    @Test
    void aStreamWhoseClientDoesNotDrainLogsHowLongItWaitedForCredit(CapturedOutput output) throws Exception {
        CompletableFuture<Received> received = new CompletableFuture<>();
        LocalService late = closeAfterTest(LocalService.start(connection -> {
            pause(UNDRAINED);
            received.complete(drain(connection.getInputStream()));
        }));
        Tunnel tunnel = openTunnel(late);
        String sessionId = tunnel.handle().sessionId();

        Socket visitor = visit(tunnel.port());
        String sent = send(visitor.getOutputStream(), HELD_BACK_UPLOAD, 5);
        visitor.shutdownOutput();
        assertThat(received.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new Received(HELD_BACK_UPLOAD, sent));
        assertThat(visitor.getInputStream().read()).isEqualTo(-1);

        await("the stream's closing is logged", () -> output.getAll().contains("stream closed: session=" + sessionId));
        String closed = output.getAll()
                .lines()
                .filter(line -> line.contains("stream closed: session=" + sessionId))
                .findFirst()
                .orElseThrow();
        assertThat(blockedMillis(closed))
                .as("time the stream waited for credit")
                .isGreaterThanOrEqualTo(Duration.ofSeconds(1).toMillis());
    }

    @Test
    void sessionErrorsAndRefusedUpgradesAreLoggedWithTheirCodes() {
        assertThat(line("WARN", "code=AUTHTOKEN_INVALID")).contains("session error: session=");
        assertThat(line("WARN", "code=HEARTBEAT_TIMEOUT")).contains("session error: session=");
        assertThat(line("INFO", "upgrade refused:")).contains("client=127.0.0.1", "code=UNSUPPORTED_PROTOCOL_VERSION");
    }

    @Test
    void noTokenOrHashOfOneIsLogged() throws Exception {
        for (String token : List.of(ALICE_TOKEN, UNKNOWN_TOKEN)) {
            assertThat(log).doesNotContain(token).doesNotContain(sha256Hex(token));
        }
        assertThat(log).doesNotContain("jb_TEST-ONLY");
    }

    @Test
    void noTunnelPayloadIsLogged() {
        assertThat(log).doesNotContain(ObservedScenario.MARKER);
    }

    /** The client's name and version come from its {@code HELLO}; only the established line may carry them. */
    @Test
    void theClientsHelloIsLoggedOnlyOnTheEstablishedLine() {
        List<String> mentions = log.lines()
                .filter(line -> line.contains(ObservedScenario.CLIENT_NAME))
                .toList();

        assertThat(mentions).singleElement().asString().contains("session established:");
    }

    /** The one line at {@code level} containing {@code text}. */
    private String line(String level, String text) {
        List<String> found = lines(level, text);
        assertThat(found).as("%s lines containing \"%s\"", level, text).hasSize(1);
        return found.getFirst();
    }

    /**
     * Lines at {@code level} containing {@code text}, in the console format or in ECS JSON, which the JVM keeps using
     * once a test has started the {@code prod} profile. JSON-escaped quotes are unescaped first.
     */
    private List<String> lines(String level, String text) {
        return log.lines()
                .map(line -> line.replace("\\\"", "\""))
                .filter(line -> (line.contains(" " + level + " ") || line.contains("\"level\":\"" + level + "\""))
                        && line.contains(text))
                .toList();
    }

    private static long blockedMillis(String streamClosed) {
        Matcher matcher = BLOCKED_MS.matcher(streamClosed);
        assertThat(matcher.find()).as("blockedMs in %s", streamClosed).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static void pause(Duration duration) throws InterruptedIOException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        }
    }

    private static String sha256Hex(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(US_ASCII)));
    }
}
