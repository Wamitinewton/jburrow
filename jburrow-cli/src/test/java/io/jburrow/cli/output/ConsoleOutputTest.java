package io.jburrow.cli.output;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.TunnelStartException;
import io.jburrow.client.TunnelStartException.Kind;
import io.jburrow.client.tunnel.domain.ExchangeSummary;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.wire.ErrorCode;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConsoleOutputTest {

    private static final RegisteredTunnel TUNNEL =
            RegisteredTunnel.of("default", UUID.randomUUID(), TunnelKind.TCP, "tcp.cloudnativehub.app", 20_417);
    private static final RegisteredTunnel OTTER =
            RegisteredTunnel.http("default", UUID.randomUUID(), "https", "quiet-otter-4f2a.example.dev", 443);
    private static final RegisteredTunnel HERON =
            RegisteredTunnel.http("default", UUID.randomUUID(), "https", "brave-heron-91c0.example.dev", 443);
    /** What the client says for each kind of failed attempt and lost session. */
    private static final String REFUSED_TEXT =
            "Couldn't connect to the server: Connection refused: localhost/127.0.0.1:7080";

    private static final String REFUSED = REFUSED_TEXT + ".";

    private static final String NO_CODE_503 = "The upgrade was refused with HTTP 503 Service Unavailable and no"
            + " JBurrow error code; something between this client and the server may have answered.";
    private static final String DROPPED = "The connection to the server was lost.";

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    // 12:01:07 in Nairobi: times are printed in the clock's zone, not UTC.
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T09:01:07Z"), ZoneId.of("Africa/Nairobi"));
    private final ConsoleOutput output = new ConsoleOutput(
            new PrintWriter(out),
            new PrintWriter(err),
            clock,
            "0.2.0",
            List.of(TunnelSpec.tcp("default", LocalTarget.of("localhost", 22))),
            true);
    private final ConsoleOutput httpOutput = http(TunnelSpec.http("default", LocalTarget.of("localhost", 3000)));

    @ParameterizedTest(name = "{0} bytes is {1}")
    @CsvSource({
        "0, 0 B",
        "999, 999 B",
        "1000, 1.0 KB",
        "3100, 3.1 KB",
        "41000, 41.0 KB",
        "999949, 999.9 KB",
        "999950, 1.0 MB",
        "1250000, 1.3 MB",
        "7300000000, 7.3 GB"
    })
    void bytesUseDecimalUnitsWithOneDecimal(long bytes, String printed) {
        assertThat(Sizes.bytes(bytes)).isEqualTo(printed);
    }

    @ParameterizedTest(name = "{0} bytes is {1} on a request line")
    @CsvSource({
        "0, 0 B",
        "52, 52 B",
        "1023, 1023 B",
        "1024, 1.0 KiB",
        "3482, 3.4 KiB",
        "90112, 88.0 KiB",
        "1048524, 1023.9 KiB",
        "1048525, 1.0 MiB",
        "1073741824, 1.0 GiB"
    })
    void requestLinesUseBinaryUnits(long bytes, String printed) {
        assertThat(Sizes.binaryBytes(bytes)).isEqualTo(printed);
    }

    @ParameterizedTest(name = "{0} ms is {1} on a request line")
    @CsvSource({"0, 0 ms", "12, 12 ms", "9999, 9999 ms", "10000, 10s", "157000, 2m37s"})
    void requestLinesUseMillisecondsUnderTenSeconds(long millis, String printed) {
        assertThat(Sizes.shortDuration(millis)).isEqualTo(printed);
    }

    @ParameterizedTest(name = "{0} ms is {1}")
    @CsvSource({
        "0, 0ms",
        "850, 850ms",
        "999, 999ms",
        "1000, 1s",
        "59999, 59s",
        "60000, 1m00s",
        "157000, 2m37s",
        "3599999, 59m59s",
        "3840000, 1h04m",
        "90000000, 25h00m"
    })
    void durationsUseTheTwoLargestUnits(long millis, String printed) {
        assertThat(Sizes.duration(millis)).isEqualTo(printed);
    }

    @Test
    void theEstablishedBlockNamesTheVersionProtocolAndForwarding() {
        output.onEstablished("session", List.of(TUNNEL));

        assertThat(out.toString().lines())
                .containsExactly(
                        "jburrow 0.2.0, protocol jburrow.v1-draft.2",
                        "Forwarding  tcp://tcp.cloudnativehub.app:20417 -> localhost:22",
                        "");
    }

    @Test
    void eachConnectionIsALineWhenItOpensAndWhenItEnds() {
        output.onStreamOpened(1, "default", "203.0.113.9", 51_234);
        output.onStreamClosed(1, 3_100, 41_000, 157_000, Optional.empty());
        output.onStreamOpened(3, "default", "2001:db8::9", 51_302);
        output.onStreamClosed(3, 0, 0, 12, Optional.of(ErrorCode.LOCAL_SERVICE_UNREACHABLE));

        assertThat(out.toString().lines())
                .containsExactly(
                        "12:01:07  203.0.113.9:51234  opened",
                        "12:01:07  203.0.113.9:51234  closed   in 3.1 KB  out 41.0 KB  2m37s",
                        "12:01:07  [2001:db8::9]:51302  opened",
                        "12:01:07  [2001:db8::9]:51302  reset    LOCAL_SERVICE_UNREACHABLE:"
                                + " nothing is listening on localhost:22");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void aStartFailureIsOneLineOnStderrWithItsAdvice() {
        output.onStartFailed(new TunnelStartException(
                Kind.UPGRADE_REFUSED,
                "This server speaks jburrow.v1-draft.3",
                Optional.of(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION),
                List.of("jburrow.v1-draft.3", "jburrow.v1-draft.4"),
                null));

        assertThat(err.toString().lines())
                .containsExactly("Error UNSUPPORTED_PROTOCOL_VERSION: This server speaks jburrow.v1-draft.3."
                        + " This server speaks jburrow.v1-draft.3, jburrow.v1-draft.4; upgrade jburrow.");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void aFailureWithoutACodeIsLabelledByItsKind() {
        output.onStartFailed(new TunnelStartException(Kind.CONNECT_FAILED, "Connection refused: localhost:7080"));

        assertThat(err.toString().strip()).isEqualTo("Error CONNECT_FAILED: Connection refused: localhost:7080.");
    }

    @Test
    void aLostConnectionWithoutReconnectSaysHowToGetIt() {
        ConsoleOutput once = new ConsoleOutput(
                new PrintWriter(out),
                new PrintWriter(err),
                clock,
                "0.2.0",
                List.of(TunnelSpec.tcp("default", LocalTarget.of("localhost", 22))),
                false);

        once.onClosed(TunnelClosed.connectionLost("The connection to the server was lost."));

        assertThat(err.toString().strip())
                .isEqualTo("Error CONNECTION_LOST: The connection to the server was lost."
                        + " Run the command again, or leave out --no-reconnect to have it reconnect by itself.");
    }

    @Test
    void aFirstSessionThatNeverCameUpDuringADeploySaysToRunTheCommandAgain() {
        output.onStartFailed(refusal(ErrorCode.SERVER_SHUTTING_DOWN, "The server is shutting down."));

        assertThat(err.toString().strip())
                .isEqualTo("Error SERVER_SHUTTING_DOWN: The server is shutting down. Run the command again.");
    }

    @Test
    void aServerErrorIsLabelledByItsCode() {
        output.onClosed(TunnelClosed.serverError(ErrorCode.SERVER_SHUTTING_DOWN, "The server is shutting down."));

        assertThat(err.toString().strip())
                .startsWith("Error SERVER_SHUTTING_DOWN: The server is shutting down. Run the command again");
    }

    @Test
    void closingOnPurposePrintsNothing() {
        output.onClosed(TunnelClosed.userClosed());

        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void anythingButPrintableAsciiFromTheServerIsAQuestionMark() {
        output.onClosed(TunnelClosed.serverError(ErrorCode.INTERNAL_ERROR, "café ☃ done"));

        assertThat(err.toString().strip()).isEqualTo("Error INTERNAL_ERROR: caf? ? done.");
    }

    // Reconnecting.

    @Test
    void aLostSessionAndItsReconnectAreOneLineEach() {
        httpOutput.onEstablished("first", List.of(OTTER));
        out.getBuffer().setLength(0);

        httpOutput.onDisconnected(shuttingDown(), Duration.ofMillis(830));
        httpOutput.onReconnected("second", List.of(OTTER), true);

        assertThat(out.toString().lines())
                .containsExactly(
                        "12:01:07  connection lost: the server is restarting; reconnecting in 0.8 s",
                        "12:01:07  reconnected   https://quiet-otter-4f2a.example.dev");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void aRunOfIdenticalFailuresPrintsOnlyItsFirst() {
        httpOutput.onEstablished("first", List.of(OTTER));
        out.getBuffer().setLength(0);

        httpOutput.onDisconnected(shuttingDown(), Duration.ofMillis(830));
        httpOutput.onDisconnected(TunnelClosed.connectionLost(REFUSED), Duration.ofMillis(1_040));
        httpOutput.onDisconnected(TunnelClosed.connectionLost(REFUSED), Duration.ofMillis(960));
        httpOutput.onDisconnected(refused503(), Duration.ofMillis(1_000));
        httpOutput.onDisconnected(refused503(), Duration.ofMillis(1_100));
        httpOutput.onDisconnected(TunnelClosed.connectionLost(REFUSED), Duration.ofMillis(900));
        httpOutput.onReconnected("second", List.of(OTTER), true);

        assertThat(out.toString().lines())
                .containsExactly(
                        "12:01:07  connection lost: the server is restarting; reconnecting in 0.8 s",
                        "12:01:07  still reconnecting: the server isn't reachable (" + REFUSED_TEXT
                                + "); next try in 1.0 s",
                        "12:01:07  still reconnecting: the server isn't reachable (503); next try in 1.0 s",
                        "12:01:07  still reconnecting: the server isn't reachable (" + REFUSED_TEXT
                                + "); next try in 0.9 s",
                        "12:01:07  reconnected   https://quiet-otter-4f2a.example.dev");
    }

    @Test
    void theStatusComesFromTheRefusalNotFromTheWordsOfItsMessage() {
        output.onEstablished("first", List.of(TUNNEL));
        output.onDisconnected(TunnelClosed.connectionLost(DROPPED), Duration.ZERO);
        out.getBuffer().setLength(0);

        output.onDisconnected(TunnelClosed.connectionLost("Proxy said HTTP 502 earlier."), Duration.ofSeconds(1));
        output.onDisconnected(
                TunnelClosed.connectionLost("Refused.").withUpgradeStatus(OptionalInt.of(504)), Duration.ofSeconds(1));

        assertThat(out.toString().lines())
                .containsExactly(
                        "12:01:07  still reconnecting: the server isn't reachable (Proxy said HTTP 502 earlier);"
                                + " next try in 1.0 s",
                        "12:01:07  still reconnecting: the server isn't reachable (504); next try in 1.0 s");
    }

    @Test
    void theNextLossAfterAReconnectIsALostLineAgain() {
        httpOutput.onEstablished("first", List.of(OTTER));
        httpOutput.onDisconnected(TunnelClosed.connectionLost(DROPPED), Duration.ofMillis(240));
        httpOutput.onReconnected("second", List.of(OTTER), true);
        out.getBuffer().setLength(0);

        httpOutput.onDisconnected(TunnelClosed.connectionLost(DROPPED), Duration.ofMillis(310));

        assertThat(out.toString().lines())
                .containsExactly("12:01:07  connection lost: the connection dropped; reconnecting in 0.3 s");
    }

    @ParameterizedTest(name = "{0} reads as \"{1}\"")
    @CsvSource({
        "SERVER_SHUTTING_DOWN, the server is restarting",
        "SERVER_AT_CAPACITY, the server is busy",
        "INTERNAL_ERROR, internal error",
        "HEARTBEAT_TIMEOUT, the server stopped answering"
    })
    void aLostSessionSaysWhyInPlainWords(ErrorCode code, String words) {
        output.onDisconnected(TunnelClosed.serverError(code, "The server says so."), Duration.ofSeconds(1));

        assertThat(out.toString().strip()).isEqualTo("12:01:07  connection lost: " + words + "; reconnecting in 1.0 s");
    }

    @Test
    void aSilentServerSeenByThisClientStoppedAnswering() {
        output.onDisconnected(
                TunnelClosed.localError(ErrorCode.HEARTBEAT_TIMEOUT, "No PONG within 45 s."), Duration.ofSeconds(2));

        assertThat(out.toString().strip())
                .isEqualTo("12:01:07  connection lost: the server stopped answering; reconnecting in 2.0 s");
    }

    @Test
    void aNewUrlSaysTheOldOneWasTaken() {
        httpOutput.onEstablished("first", List.of(OTTER));
        httpOutput.onDisconnected(TunnelClosed.connectionLost(DROPPED), Duration.ofSeconds(30));
        out.getBuffer().setLength(0);

        httpOutput.onDisconnected(
                TunnelClosed.serverError(ErrorCode.SUBDOMAIN_UNAVAILABLE, "The subdomain is in use."), Duration.ZERO);
        httpOutput.onReconnected("second", List.of(HERON), false);

        assertThat(out.toString().lines())
                .containsExactly(
                        "12:01:07  still reconnecting: the old URL is taken, asking for a new one; next try in 0.0 s",
                        "12:01:07  reconnected with a new URL  https://brave-heron-91c0.example.dev"
                                + "  (the old one was taken)");
    }

    @Test
    void aClientThatGivesUpWhileReconnectingEndsWithTheError() {
        output.onEstablished("first", List.of(TUNNEL));
        output.onDisconnected(TunnelClosed.connectionLost(DROPPED), Duration.ofMillis(200));

        output.onClosed(TunnelClosed.serverError(ErrorCode.AUTHTOKEN_INVALID, "The authtoken is not valid."));

        assertThat(err.toString().strip())
                .isEqualTo("Error AUTHTOKEN_INVALID: The authtoken is not valid."
                        + " Check the token; tokens are shown once when created.");
    }

    // HTTP tunnels.

    @Test
    void anHttpTunnelForwardsToTheLocalServiceAsAUrl() {
        httpOutput.onEstablished(
                "session",
                List.of(RegisteredTunnel.http(
                        "default", UUID.randomUUID(), "https", "quiet-otter-4f2a.cloudnativehub.app", 443)));

        assertThat(out.toString().lines())
                .containsExactly(
                        "jburrow 0.2.0, protocol jburrow.v1-draft.2",
                        "Forwarding  https://quiet-otter-4f2a.cloudnativehub.app -> http://localhost:3000",
                        "");
    }

    @Test
    void eachRequestIsOneLineWhenItEndsAndItsStreamPrintsNothing() {
        httpOutput.onStreamOpened(1, "default", "203.0.113.9", 51_234);
        httpOutput.onExchangeCompleted(exchange(1, "GET", "/", OptionalInt.of(200), Optional.empty(), 3_482, 12));
        httpOutput.onStreamClosed(1, 0, 3_482, 12, Optional.empty());
        httpOutput.onStreamOpened(3, "default", "203.0.113.9", 51_236);
        httpOutput.onExchangeCompleted(
                exchange(3, "GET", "/assets/app.js", OptionalInt.of(200), Optional.empty(), 90_112, 31));
        httpOutput.onStreamClosed(3, 0, 90_112, 31, Optional.empty());
        httpOutput.onExchangeCompleted(exchange(5, "POST", "/api/items", OptionalInt.of(201), Optional.empty(), 52, 8));

        assertThat(out.toString().lines())
                .containsExactly(
                        "12:01:07  GET  /                      200   3.4 KiB    12 ms",
                        "12:01:07  GET  /assets/app.js         200  88.0 KiB    31 ms",
                        "12:01:07  POST /api/items             201      52 B     8 ms");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void aRequestResetBeforeItsResponsePrintsNoStatusOrSizeAndTheReason() {
        httpOutput.onExchangeCompleted(exchange(
                1, "GET", "/missing", OptionalInt.empty(), Optional.of(ErrorCode.LOCAL_SERVICE_UNREACHABLE), 0, 3));

        assertThat(out.toString().lines())
                .containsExactly(
                        "12:01:07  GET  /missing                 -         -     3 ms  local service unreachable");
    }

    @Test
    void aResponseResetMidwayKeepsItsStatusAndTheBytesSoFar() {
        httpOutput.onExchangeCompleted(exchange(
                1, "GET", "/video", OptionalInt.of(200), Optional.of(ErrorCode.LOCAL_SERVICE_ERROR), 2_048, 15_000));

        assertThat(out.toString().strip())
                .isEqualTo("12:01:07  GET  /video                 200   2.0 KiB      15s  local service error");
    }

    @Test
    void aLongTargetIsCutToSixtyCharacters() {
        String target = "/search?q=" + "x".repeat(80);

        httpOutput.onExchangeCompleted(exchange(1, "GET", target, OptionalInt.of(200), Optional.empty(), 0, 1));

        String line = out.toString().strip();
        assertThat(line).contains(target.substring(0, 57) + "...  200").doesNotContain(target.substring(0, 58));
    }

    @Test
    void aTargetIsPrintedAsAsciiOnly() {
        httpOutput.onExchangeCompleted(
                exchange(1, "GET", "/\u001b[31mred\u00e9", OptionalInt.of(200), Optional.empty(), 0, 1));

        assertThat(out.toString().strip()).startsWith("12:01:07  GET  /?[31mred?");
    }

    @Test
    void aSubdomainInUseNamesItAndSaysWhatToDo() {
        http(TunnelSpec.http("default", LocalTarget.of("localhost", 3000)).subdomain(new SubdomainName("myapp")))
                .onStartFailed(refusal(ErrorCode.SUBDOMAIN_UNAVAILABLE, "The subdomain is in use."));

        assertThat(err.toString().strip())
                .isEqualTo("Error SUBDOMAIN_UNAVAILABLE: The subdomain myapp is in use."
                        + " Try another, or leave out --subdomain.");
    }

    @Test
    void aForbiddenSubdomainNamesIt() {
        http(TunnelSpec.http("default", LocalTarget.of("localhost", 3000)).subdomain(new SubdomainName("paypal-login")))
                .onStartFailed(refusal(ErrorCode.SUBDOMAIN_FORBIDDEN, "The subdomain is reserved or blocked."));

        assertThat(err.toString().strip())
                .isEqualTo("Error SUBDOMAIN_FORBIDDEN: The subdomain paypal-login isn't allowed on this server.");
    }

    @Test
    void aServerWithoutHttpTunnelsSaysWhatToDo() {
        httpOutput.onStartFailed(
                new TunnelStartException(Kind.UNSUPPORTED_BY_SERVER, "This server doesn't support HTTP tunnels."));

        assertThat(err.toString().strip())
                .isEqualTo("Error UNSUPPORTED_BY_SERVER: This server doesn't support HTTP tunnels."
                        + " Upgrade the server, or use jburrow tcp.");
    }

    private ConsoleOutput http(TunnelSpec tunnel) {
        return new ConsoleOutput(new PrintWriter(out), new PrintWriter(err), clock, "0.2.0", List.of(tunnel), true);
    }

    private static TunnelClosed refused503() {
        return TunnelClosed.connectionLost(NO_CODE_503).withUpgradeStatus(OptionalInt.of(503));
    }

    private static TunnelClosed shuttingDown() {
        return TunnelClosed.serverError(ErrorCode.SERVER_SHUTTING_DOWN, "The server is shutting down.");
    }

    private static ExchangeSummary exchange(
            int streamId,
            String method,
            String target,
            OptionalInt status,
            Optional<ErrorCode> reset,
            long responseBytes,
            long millis) {
        return new ExchangeSummary(streamId, method, target, status, reset, 0, responseBytes, millis);
    }

    private static TunnelStartException refusal(ErrorCode code, String message) {
        return new TunnelStartException(Kind.SERVER_ERROR, message, Optional.of(code), List.of(), null);
    }
}
