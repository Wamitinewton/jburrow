package io.jburrow.cli.output;

import io.jburrow.client.TunnelStartException;
import io.jburrow.client.tunnel.domain.ExchangeSummary;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.control.SubdomainName;
import io.jburrow.protocol.control.TunnelKind;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolVersion;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code --output human}: the forwarding block and one line per TCP connection or HTTP request on stdout, errors on
 * stderr as one line with the code and, for the common ones, what to do about it. ASCII only, so every console renders
 * it; any other character a server or a visitor sent is printed as {@code ?}.
 *
 * <p>A request line is printed when its exchange ends, and its columns stay where they are: scripts read it.
 *
 * <pre>
 * 14:02:11  GET  /                      200   3.4 KiB    12 ms
 * 14:02:14  GET  /missing                 -         -     3 ms  local service unreachable
 * </pre>
 */
public final class ConsoleOutput implements Output {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final String RECONNECT_ADVICE =
            "Run the command again; automatic reconnect arrives in a later release.";
    private static final int MAX_TARGET = 60;
    private static final String CUT = "...";
    private static final String NONE = "-";

    private final PrintWriter out;
    private final PrintWriter err;
    private final Clock clock;
    private final String version;
    private final Map<String, TunnelSpec> tunnels;
    /** Only touched on the client's I/O thread, which delivers every stream event. */
    private final Map<Integer, OpenStream> streams = new HashMap<>();
    /** HTTP streams print one line, from their exchange, so their stream events print nothing. Same thread. */
    private final Set<Integer> exchanges = new HashSet<>();

    private record OpenStream(String remote, LocalTarget target) {}

    /**
     * @param clock its zone is the one times are printed in
     * @param version this CLI's version, for the first line
     * @param tunnels the tunnels the command asked for
     */
    public ConsoleOutput(PrintWriter out, PrintWriter err, Clock clock, String version, List<TunnelSpec> tunnels) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.version = Objects.requireNonNull(version, "version");
        this.tunnels = tunnels.stream().collect(Collectors.toUnmodifiableMap(TunnelSpec::ref, Function.identity()));
    }

    @Override
    public void onEstablished(String sessionId, List<RegisteredTunnel> registered) {
        line(out, "jburrow " + version + ", protocol " + ProtocolVersion.CURRENT);
        for (RegisteredTunnel tunnel : registered) {
            line(out, "Forwarding  " + tunnel.url() + " -> " + local(tunnels.get(tunnel.ref())));
        }
        line(out, "");
    }

    @Override
    public void onStreamOpened(int streamId, String tunnelRef, String remoteAddress, int remotePort) {
        TunnelSpec tunnel = tunnels.get(tunnelRef);
        if (tunnel != null && tunnel.kind() == TunnelKind.HTTP) {
            exchanges.add(streamId);
            return;
        }
        String remote = Sizes.endpoint(remoteAddress, remotePort);
        streams.put(streamId, new OpenStream(remote, tunnel == null ? null : tunnel.target()));
        line(out, now() + "  " + remote + "  opened");
    }

    @Override
    public void onStreamClosed(
            int streamId, long bytesIn, long bytesOut, long durationMillis, Optional<ErrorCode> reset) {
        if (exchanges.remove(streamId)) {
            return;
        }
        OpenStream stream = streams.remove(streamId);
        String remote = stream == null ? "stream " + streamId : stream.remote();
        if (reset.isPresent()) {
            String reason = resetReason(reset.get(), stream == null ? null : stream.target());
            line(out, now() + "  " + remote + "  reset    " + reset.get() + (reason == null ? "" : ": " + reason));
        } else {
            line(
                    out,
                    now() + "  " + remote + "  closed   in " + Sizes.bytes(bytesIn) + "  out " + Sizes.bytes(bytesOut)
                            + "  " + Sizes.duration(durationMillis));
        }
    }

    @Override
    public void onExchangeCompleted(ExchangeSummary exchange) {
        boolean answered = exchange.status().isPresent();
        String line = String.format(
                Locale.ROOT,
                "%s  %-4s %-21s  %3s  %8s  %7s",
                now(),
                exchange.method(),
                target(exchange.target()),
                answered ? String.valueOf(exchange.status().getAsInt()) : NONE,
                answered ? Sizes.binaryBytes(exchange.responseBytes()) : NONE,
                Sizes.shortDuration(exchange.durationMillis()));
        line(
                out,
                line + exchange.reset().map(code -> "  " + exchangeReset(code)).orElse(""));
    }

    @Override
    public void onClosed(TunnelClosed closed) {
        if (closed.reason() == TunnelClosed.Reason.USER_CLOSED) {
            return;
        }
        String label = closed.code().map(ErrorCode::name).orElse(closed.reason().name());
        error(label, closed.message(), List.of());
    }

    @Override
    public void onStartFailed(TunnelStartException failure) {
        String label = failure.code().map(ErrorCode::name).orElse(failure.kind().name());
        error(label, failure.getMessage(), failure.supportedVersions());
    }

    @Override
    public void onUsageError(String message) {}

    @Override
    public void onInternalError(Throwable failure) {}

    private void error(String label, String message, List<String> supportedVersions) {
        String advice = advice(label, supportedVersions);
        line(err, "Error " + label + ": " + sentence(explained(label, message)) + (advice == null ? "" : " " + advice));
    }

    /** The server's refusal of a requested name doesn't repeat the name; this output knows it. */
    private String explained(String label, String message) {
        Optional<SubdomainName> name = tunnels.values().stream()
                .flatMap(tunnel -> tunnel.subdomain().stream())
                .findFirst();
        return switch (label) {
            case "SUBDOMAIN_UNAVAILABLE" ->
                name.map(n -> "The subdomain " + n + " is in use.").orElse(message);
            case "SUBDOMAIN_FORBIDDEN" ->
                name.map(n -> "The subdomain " + n + " isn't allowed on this server.")
                        .orElse(message);
            default -> message;
        };
    }

    /** Advice for the failures a user can do something about; a label is an error code, or a reason without one. */
    private static String advice(String label, List<String> supportedVersions) {
        return switch (label) {
            case "AUTHTOKEN_INVALID" -> "Check the token; tokens are shown once when created.";
            case "UNSUPPORTED_PROTOCOL_VERSION" ->
                supportedVersions.isEmpty()
                        ? "Upgrade jburrow."
                        : "This server speaks " + String.join(", ", supportedVersions) + "; upgrade jburrow.";
            case "PORT_UNAVAILABLE" -> "Try without --remote-port.";
            case "SUBDOMAIN_UNAVAILABLE" -> "Try another, or leave out --subdomain.";
            case "UNSUPPORTED_BY_SERVER" -> "Upgrade the server, or use jburrow tcp.";
            case "CONNECTION_LOST", "HEARTBEAT_TIMEOUT", "SERVER_SHUTTING_DOWN" -> RECONNECT_ADVICE;
            default -> null;
        };
    }

    private static String resetReason(ErrorCode code, LocalTarget target) {
        return switch (code) {
            case LOCAL_SERVICE_UNREACHABLE -> "nothing is listening on " + (target == null ? "the local port" : target);
            case PEER_RESET -> "the connection was reset";
            case CANCELLED -> "the tunnel closed";
            case STREAM_CLOSED -> "data arrived after the stream closed";
            case INTERNAL_ERROR -> "an internal error";
            default -> null;
        };
    }

    /** The code in words: {@code LOCAL_SERVICE_UNREACHABLE} is {@code local service unreachable}. */
    private static String exchangeReset(ErrorCode code) {
        return code.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String target(String target) {
        return target.length() <= MAX_TARGET ? target : target.substring(0, MAX_TARGET - CUT.length()) + CUT;
    }

    private static String local(TunnelSpec tunnel) {
        if (tunnel == null) {
            return "?";
        }
        return switch (tunnel.kind()) {
            case TCP -> tunnel.target().toString();
            case HTTP -> "http://" + tunnel.target();
        };
    }

    private static String sentence(String message) {
        String text = message.strip();
        if (text.isEmpty()) {
            return "";
        }
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?' ? text : text + ".";
    }

    private String now() {
        return LocalTime.now(clock).format(TIME);
    }

    private static void line(PrintWriter writer, String text) {
        writer.println(ascii(text));
        writer.flush();
    }

    private static String ascii(String text) {
        StringBuilder printable = new StringBuilder(text.length());
        text.codePoints().forEach(c -> printable.append(c >= ' ' && c <= '~' ? (char) c : '?'));
        return printable.toString();
    }
}
