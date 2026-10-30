package io.jburrow.cli.output;

import io.jburrow.client.TunnelStartException;
import io.jburrow.client.tunnel.domain.ExchangeSummary;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.wire.ErrorCode;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code --output json}: one JSON object per line on stdout, {@code type} first, times in ISO-8601 UTC. Every run ends
 * with exactly one {@code closed} line, whether the session ended, never started, or the arguments were refused, so a
 * script always gets a final event. An HTTP tunnel's stream also has a {@code request} line, just before its
 * {@code stream_closed}.
 *
 * <p>These events aren't protocol messages, so they have their own mapper rather than the protocol's strict one. Its
 * output is ASCII: anything else is escaped.
 */
public final class JsonLinesOutput implements Output {

    /** {@code closed.reason} when the arguments were refused, before any connection. */
    public static final String USAGE_ERROR = "USAGE_ERROR";
    /** {@code closed.reason} when the command failed in a way it doesn't expect. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .build();

    private final PrintWriter out;
    private final Clock clock;
    private final Map<String, LocalTarget> targets;

    record Registered(String type, String time, String sessionId, List<Tunnel> tunnels) {}

    record Tunnel(String ref, String url, String publicHost, int publicPort, String local) {}

    record StreamOpened(String type, String time, int streamId, String remote) {}

    record StreamClosed(
            String type, String time, int streamId, long bytesIn, long bytesOut, long durationMs, String reset) {}

    /** {@code status} is null until a final response head arrived; {@code reset} unless the response was complete. */
    record Request(
            String type,
            String time,
            int streamId,
            String method,
            String target,
            Integer status,
            String reset,
            long requestBytes,
            long responseBytes,
            long durationMs) {}

    record Closed(String type, String time, String reason, String code, String message) {}

    /** @param tunnels the tunnels the command asked for */
    public JsonLinesOutput(PrintWriter out, Clock clock, List<TunnelSpec> tunnels) {
        this.out = Objects.requireNonNull(out, "out");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.targets = tunnels.stream().collect(Collectors.toUnmodifiableMap(TunnelSpec::ref, TunnelSpec::target));
    }

    @Override
    public void onEstablished(String sessionId, List<RegisteredTunnel> tunnels) {
        List<Tunnel> entries = tunnels.stream()
                .map(tunnel -> new Tunnel(
                        tunnel.ref(),
                        tunnel.url(),
                        tunnel.publicHost(),
                        tunnel.publicPort(),
                        String.valueOf(targets.get(tunnel.ref()))))
                .toList();
        write(new Registered("registered", now(), sessionId, entries));
    }

    @Override
    public void onStreamOpened(int streamId, String tunnelRef, String remoteAddress, int remotePort) {
        write(new StreamOpened("stream_opened", now(), streamId, Sizes.endpoint(remoteAddress, remotePort)));
    }

    @Override
    public void onStreamClosed(
            int streamId, long bytesIn, long bytesOut, long durationMillis, Optional<ErrorCode> reset) {
        write(new StreamClosed(
                "stream_closed",
                now(),
                streamId,
                bytesIn,
                bytesOut,
                durationMillis,
                reset.map(ErrorCode::name).orElse(null)));
    }

    @Override
    public void onExchangeCompleted(ExchangeSummary exchange) {
        write(new Request(
                "request",
                now(),
                exchange.streamId(),
                exchange.method(),
                exchange.target(),
                exchange.status().isPresent() ? exchange.status().getAsInt() : null,
                exchange.reset().map(ErrorCode::name).orElse(null),
                exchange.requestBytes(),
                exchange.responseBytes(),
                exchange.durationMillis()));
    }

    @Override
    public void onClosed(TunnelClosed closed) {
        closed(closed.reason().name(), closed.code(), closed.message());
    }

    @Override
    public void onStartFailed(TunnelStartException failure) {
        closed(failure.kind().name(), failure.code(), failure.getMessage());
    }

    @Override
    public void onUsageError(String message) {
        closed(USAGE_ERROR, Optional.empty(), message);
    }

    @Override
    public void onInternalError(Throwable failure) {
        closed(INTERNAL_ERROR, Optional.empty(), String.valueOf(failure.getMessage()));
    }

    private void closed(String reason, Optional<ErrorCode> code, String message) {
        write(new Closed("closed", now(), reason, code.map(ErrorCode::name).orElse(null), message));
    }

    private String now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private void write(Object event) {
        out.println(MAPPER.writeValueAsString(event));
        out.flush();
    }
}
