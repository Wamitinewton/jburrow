package io.jburrow.cli.output;

import io.jburrow.client.TunnelStartException;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.control.RegisteredTunnel;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ProtocolVersion;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code --output human}: the forwarding block and one line per connection on stdout, errors on stderr as one line
 * with the code and, for the common ones, what to do about it. ASCII only, so every console renders it; any other
 * character a server sent is printed as {@code ?}.
 */
public final class ConsoleOutput implements Output {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final String RECONNECT_ADVICE =
            "Run the command again; automatic reconnect arrives in a later release.";

    private final PrintWriter out;
    private final PrintWriter err;
    private final Clock clock;
    private final String version;
    private final Map<String, LocalTarget> targets;
    /** Only touched on the client's I/O thread, which delivers every stream event. */
    private final Map<Integer, OpenStream> streams = new HashMap<>();

    private record OpenStream(String remote, LocalTarget target) {}

    /**
     * @param clock its zone is the one times are printed in
     * @param version this CLI's version, for the first line
     * @param targets each tunnel's local target, by its {@code ref}
     */
    public ConsoleOutput(
            PrintWriter out, PrintWriter err, Clock clock, String version, Map<String, LocalTarget> targets) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.version = Objects.requireNonNull(version, "version");
        this.targets = Map.copyOf(targets);
    }

    @Override
    public void onEstablished(String sessionId, List<RegisteredTunnel> tunnels) {
        line(out, "jburrow " + version + ", protocol " + ProtocolVersion.CURRENT);
        for (RegisteredTunnel tunnel : tunnels) {
            line(out, "Forwarding  " + tunnel.url() + " -> " + targets.get(tunnel.ref()));
        }
        line(out, "");
    }

    @Override
    public void onStreamOpened(int streamId, String tunnelRef, String remoteAddress, int remotePort) {
        String remote = Sizes.endpoint(remoteAddress, remotePort);
        streams.put(streamId, new OpenStream(remote, targets.get(tunnelRef)));
        line(out, now() + "  " + remote + "  opened");
    }

    @Override
    public void onStreamClosed(
            int streamId, long bytesIn, long bytesOut, long durationMillis, Optional<ErrorCode> reset) {
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
        line(err, "Error " + label + ": " + sentence(message) + (advice == null ? "" : " " + advice));
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
