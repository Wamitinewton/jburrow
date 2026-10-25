package io.jburrow.cli.output;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.client.TunnelStartException;
import io.jburrow.client.TunnelStartException.Kind;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.wire.ErrorCode;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Each event's exact shape: scripts and the native smoke test parse these lines. */
class JsonLinesOutputTest {

    private static final String TIME = "2026-10-25T12:01:07.123Z";

    private final StringWriter out = new StringWriter();
    // Not UTC, and finer than milliseconds: neither may show in the output.
    private final Clock clock =
            Clock.fixed(Instant.parse("2026-10-25T12:01:07.123456Z"), ZoneId.of("America/New_York"));
    private final JsonLinesOutput output =
            new JsonLinesOutput(new PrintWriter(out), clock, Map.of("default", LocalTarget.of("localhost", 22)));

    @Test
    void registered() {
        output.onEstablished(
                "0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a",
                List.of(RegisteredTunnel.of(
                        "default", UUID.randomUUID(), TunnelKind.TCP, "tcp.cloudnativehub.app", 20_417)));

        assertThat(onlyLine())
                .isEqualTo("{\"type\":\"registered\",\"time\":\"" + TIME + "\","
                        + "\"sessionId\":\"0b8e5c1e-7c55-4c46-9a3e-2f1d0c9e4b7a\","
                        + "\"tunnels\":[{\"ref\":\"default\",\"url\":\"tcp://tcp.cloudnativehub.app:20417\","
                        + "\"publicHost\":\"tcp.cloudnativehub.app\",\"publicPort\":20417,"
                        + "\"local\":\"localhost:22\"}]}");
    }

    @Test
    void streamOpened() {
        output.onStreamOpened(1, "default", "203.0.113.9", 51_234);

        assertThat(onlyLine())
                .isEqualTo("{\"type\":\"stream_opened\",\"time\":\"" + TIME + "\",\"streamId\":1,"
                        + "\"remote\":\"203.0.113.9:51234\"}");
    }

    @Test
    void streamClosed() {
        output.onStreamClosed(1, 3_100, 41_000, 157_000, Optional.empty());

        assertThat(onlyLine())
                .isEqualTo("{\"type\":\"stream_closed\",\"time\":\"" + TIME + "\",\"streamId\":1,"
                        + "\"bytesIn\":3100,\"bytesOut\":41000,\"durationMs\":157000,\"reset\":null}");
    }

    @Test
    void streamReset() {
        output.onStreamClosed(3, 0, 0, 12, Optional.of(ErrorCode.LOCAL_SERVICE_UNREACHABLE));

        assertThat(onlyLine()).endsWith(",\"reset\":\"LOCAL_SERVICE_UNREACHABLE\"}");
    }

    @Test
    void closedByTheUser() {
        output.onClosed(TunnelClosed.userClosed());

        assertThat(onlyLine())
                .isEqualTo("{\"type\":\"closed\",\"time\":\"" + TIME + "\",\"reason\":\"USER_CLOSED\","
                        + "\"code\":null,\"message\":\"The tunnel was closed.\"}");
    }

    @Test
    void closedByAServerError() {
        output.onClosed(TunnelClosed.serverError(ErrorCode.SERVER_SHUTTING_DOWN, "The server is shutting down."));

        assertThat(onlyLine())
                .isEqualTo("{\"type\":\"closed\",\"time\":\"" + TIME + "\",\"reason\":\"SERVER_ERROR\","
                        + "\"code\":\"SERVER_SHUTTING_DOWN\",\"message\":\"The server is shutting down.\"}");
    }

    @Test
    void aStartFailureIsAClosedEventNamingItsKind() {
        output.onStartFailed(new TunnelStartException(
                Kind.SERVER_ERROR,
                "The authtoken is not valid.",
                Optional.of(ErrorCode.AUTHTOKEN_INVALID),
                List.of(),
                null));

        assertThat(onlyLine())
                .isEqualTo("{\"type\":\"closed\",\"time\":\"" + TIME + "\",\"reason\":\"SERVER_ERROR\","
                        + "\"code\":\"AUTHTOKEN_INVALID\",\"message\":\"The authtoken is not valid.\"}");
    }

    @Test
    void aUsageErrorIsAClosedEvent() {
        output.onUsageError("No authtoken.");

        assertThat(onlyLine())
                .isEqualTo("{\"type\":\"closed\",\"time\":\"" + TIME + "\",\"reason\":\"USAGE_ERROR\","
                        + "\"code\":null,\"message\":\"No authtoken.\"}");
    }

    @Test
    void anInternalErrorIsAClosedEvent() {
        output.onInternalError(new IllegalStateException("broken"));

        assertThat(onlyLine()).contains("\"reason\":\"INTERNAL_ERROR\",\"code\":null,\"message\":\"broken\"");
    }

    @Test
    void theOutputIsAscii() {
        output.onClosed(TunnelClosed.serverError(ErrorCode.INTERNAL_ERROR, "café"));

        assertThat(onlyLine()).contains("\"message\":\"caf\\u00E9\"");
    }

    private String onlyLine() {
        List<String> lines = out.toString().lines().toList();
        assertThat(lines).hasSize(1);
        return lines.getFirst();
    }
}
