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
import java.time.Duration;
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
 *
 * <p>A lost session is one line, and so is the reconnect; between them, a failed attempt prints only when its reason
 * differs from the last one printed, so an outage doesn't fill the terminal:
 *
 * <pre>
 * 14:05:40  connection lost: the server is restarting; reconnecting in 0.8 s
 * 14:05:41  still reconnecting: the server isn't reachable (503); next try in 1.0 s
 * 14:05:47  reconnected   https://quiet-otter-4f2a.example.dev
 * </pre>
 */
public final class ConsoleOutput implements Output {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final String RUN_AGAIN = "Run the command again.";
    private static final String RUN_AGAIN_RECONNECTING =
            "Run the command again, or leave out --no-reconnect to have it reconnect by itself.";

    private static final int MAX_TARGET = 60;
    private static final String CUT = "...";
    private static final String NONE = "-";

    private final PrintWriter out;
    private final PrintWriter err;
    private final Clock clock;
    private final String version;
    private final Map<String, TunnelSpec> tunnels;
    private final boolean reconnects;
    /** Only touched on the client's I/O thread, which delivers every session and stream event. */
    private final Map<Integer, OpenStream> streams = new HashMap<>();
    /** HTTP streams print one line, from their exchange, so their stream events print nothing. Same thread. */
    private final Set<Integer> exchanges = new HashSet<>();
    /** The URL each tunnel had in the last session, by ref. Same thread. */
    private final Map<String, String> urls = new HashMap<>();
    /** The reason last printed since the session was lost; null while a session is up. Same thread. */
    private String lostBecause;

    private record OpenStream(String remote, LocalTarget target) {}

    /**
     * @param clock its zone is the one times are printed in
     * @param version this CLI's version, for the first line
     * @param tunnels the tunnels the command asked for
     * @param reconnects whether the client starts another session when one ends, which changes the advice for a lost
     *     one
     */
    public ConsoleOutput(
            PrintWriter out,
            PrintWriter err,
            Clock clock,
            String version,
            List<TunnelSpec> tunnels,
            boolean reconnects) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.version = Objects.requireNonNull(version, "version");
        this.tunnels = tunnels.stream().collect(Collectors.toUnmodifiableMap(TunnelSpec::ref, Function.identity()));
        this.reconnects = reconnects;
    }

    @Override
    public void onEstablished(String sessionId, List<RegisteredTunnel> registered) {
        line(out, "jburrow " + version + ", protocol " + ProtocolVersion.CURRENT);
        for (RegisteredTunnel tunnel : registered) {
            line(out, "Forwarding  " + tunnel.url() + " -> " + local(tunnels.get(tunnel.ref())));
            urls.put(tunnel.ref(), tunnel.url());
        }
        line(out, "");
    }

    /**
     * The first call after a session was up is that session's end; every later one, until {@link #onReconnected}, is
     * an attempt that failed.
     */
    @Override
    public void onDisconnected(TunnelClosed reason, Duration retryIn) {
        boolean sessionEnded = lostBecause == null;
        String because = inWords(reason, !sessionEnded);
        if (sessionEnded) {
            line(out, now() + "  connection lost: " + because + "; reconnecting in " + seconds(retryIn));
        } else if (!because.equals(lostBecause)) {
            line(out, now() + "  still reconnecting: " + because + "; next try in " + seconds(retryIn));
        }
        lostBecause = because;
    }

    @Override
    public void onReconnected(String sessionId, List<RegisteredTunnel> registered, boolean sameUrls) {
        lostBecause = null;
        for (RegisteredTunnel tunnel : registered) {
            String before = urls.put(tunnel.ref(), tunnel.url());
            if (sameUrls || tunnel.url().equals(before)) {
                line(out, now() + "  reconnected   " + tunnel.url());
            } else {
                line(out, now() + "  reconnected with a new URL  " + tunnel.url() + "  (the old one was taken)");
            }
        }
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
        line(out, line + exchange.reset().map(code -> "  " + words(code)).orElse(""));
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
    private String advice(String label, List<String> supportedVersions) {
        return switch (label) {
            case "AUTHTOKEN_INVALID" -> "Check the token; tokens are shown once when created.";
            case "UNSUPPORTED_PROTOCOL_VERSION" ->
                supportedVersions.isEmpty()
                        ? "Upgrade jburrow."
                        : "This server speaks " + String.join(", ", supportedVersions) + "; upgrade jburrow.";
            case "PORT_UNAVAILABLE" -> "Try without --remote-port.";
            case "SUBDOMAIN_UNAVAILABLE" -> "Try another, or leave out --subdomain.";
            case "UNSUPPORTED_BY_SERVER" -> "Upgrade the server, or use jburrow tcp.";
            case "CONNECTION_LOST", "HEARTBEAT_TIMEOUT", "SERVER_SHUTTING_DOWN" ->
                reconnects ? RUN_AGAIN : RUN_AGAIN_RECONNECTING;
            default -> null;
        };
    }

    /**
     * Why a session ended, or why an attempt failed, in a few words. A lost connection reads differently for each: a
     * session's connection dropped, an attempt's server couldn't be reached.
     */
    private static String inWords(TunnelClosed closed, boolean attempt) {
        if (closed.reason() == TunnelClosed.Reason.HEARTBEAT_TIMEOUT) {
            return "the server stopped answering";
        }
        if (closed.code().isPresent()) {
            return switch (closed.code().get()) {
                case SERVER_SHUTTING_DOWN -> "the server is restarting";
                case SERVER_AT_CAPACITY -> "the server is busy";
                case SUBDOMAIN_UNAVAILABLE, PORT_UNAVAILABLE -> "the old URL is taken, asking for a new one";
                default -> words(closed.code().get());
            };
        }
        if (closed.reason() == TunnelClosed.Reason.CONNECTION_LOST) {
            if (!attempt) {
                return "the connection dropped";
            }
            String cause = closed.upgradeStatus().isPresent()
                    ? String.valueOf(closed.upgradeStatus().getAsInt())
                    : withoutFullStop(closed.message());
            return "the server isn't reachable (" + cause + ")";
        }
        return withoutFullStop(closed.message());
    }

    private static String withoutFullStop(String message) {
        String text = message.strip();
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /** Whole seconds and tenths: {@code 0.8 s}, {@code 30.0 s}. */
    private static String seconds(Duration delay) {
        return String.format(Locale.ROOT, "%.1f s", delay.toMillis() / 1000.0);
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
    private static String words(ErrorCode code) {
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
