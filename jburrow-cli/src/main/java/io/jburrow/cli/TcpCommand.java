package io.jburrow.cli;

import io.jburrow.cli.output.ConsoleOutput;
import io.jburrow.cli.output.JsonLinesOutput;
import io.jburrow.cli.output.Output;
import io.jburrow.client.JBurrowClient;
import io.jburrow.client.TunnelHandle;
import io.jburrow.client.TunnelStartException;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import java.net.URI;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code jburrow tcp [host:]port}: opens one TCP tunnel, reports it until the session ends, and exits with the code
 * {@link ExitCodes} gives the outcome. Everything about the session itself is {@code jburrow-client-core}'s.
 */
@Command(
        name = "tcp",
        mixinStandardHelpOptions = true,
        versionProvider = BuildInfo.class,
        description = "Expose a local TCP port through a public port on the JBurrow server.")
final class TcpCommand implements Callable<Integer> {

    static final String AUTHTOKEN_VARIABLE = "JBURROW_AUTHTOKEN";
    static final String CLIENT_NAME = "jburrow-cli";
    static final String TUNNEL_REF = "default";

    enum Format {
        HUMAN,
        JSON;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Spec
    private CommandSpec spec;

    @Parameters(
            index = "0",
            paramLabel = "[host:]port",
            converter = LocalAddressArgument.class,
            description = "The local service: a port on localhost, or host:port.")
    private LocalTarget target;

    @Option(
            names = "--server",
            paramLabel = "<url>",
            defaultValue = "${env:" + ServerUrl.ENVIRONMENT_VARIABLE + ":-" + ServerUrl.DEFAULT + "}",
            converter = ServerUrl.class,
            description = "The server's connect URL (default: ${DEFAULT-VALUE}; environment: "
                    + ServerUrl.ENVIRONMENT_VARIABLE + ").")
    private URI server;

    // Not a picocli ${env:} default: picocli's tracing prints the values it resolves.
    @Option(
            names = "--authtoken",
            paramLabel = "<token>",
            description = "The authtoken. Prefer the " + AUTHTOKEN_VARIABLE + " environment variable: a command-line"
                    + " argument is visible to every user of this machine.")
    private String authtoken;

    @Option(
            names = "--remote-port",
            paramLabel = "<port>",
            description = "Ask for this public port (default: any free port).")
    private Integer remotePort;

    @Option(
            names = "--output",
            paramLabel = "human|json",
            defaultValue = "human",
            description = "human, or json: one JSON object per line (default: ${DEFAULT-VALUE}).")
    private Format format;

    private final UnaryOperator<String> environment;
    private final StopSignal stopSignal;
    private final Clock clock;

    TcpCommand() {
        this(System::getenv, new ShutdownHook(), Clock.systemDefaultZone());
    }

    TcpCommand(UnaryOperator<String> environment, StopSignal stopSignal, Clock clock) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.stopSignal = Objects.requireNonNull(stopSignal, "stopSignal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Integer call() {
        Output output = output();
        try {
            return run(output);
        } catch (ParameterException e) {
            output.onUsageError(e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            output.onInternalError(e);
            throw e;
        }
    }

    private int run(Output output) {
        try (JBurrowClient client = client(output)) {
            TunnelHandle handle;
            try {
                handle = client.start();
            } catch (TunnelStartException e) {
                output.onStartFailed(e);
                return ExitCodes.of(e);
            }
            CompletableFuture<Integer> exitCode = new CompletableFuture<>();
            stopSignal.install(handle::close, exitCode);
            try {
                // closed() completes after onClosed has been reported, so the final event is already out.
                TunnelClosed closed = handle.closed().toCompletableFuture().join();
                int code = ExitCodes.of(closed);
                exitCode.complete(code);
                return code;
            } finally {
                stopSignal.uninstall();
            }
        }
    }

    private JBurrowClient client(Output output) {
        String token = authtoken != null ? authtoken : environment.apply(AUTHTOKEN_VARIABLE);
        if (token == null || token.isBlank()) {
            throw usage("No authtoken. Set " + AUTHTOKEN_VARIABLE + " or pass --authtoken.");
        }
        try {
            TunnelSpec tunnel = TunnelSpec.tcp(TUNNEL_REF, target);
            if (remotePort != null) {
                tunnel = tunnel.remotePort(remotePort);
            }
            return JBurrowClient.builder()
                    .server(server)
                    .authtoken(token)
                    .tunnel(tunnel)
                    .events(output)
                    .clientInfo(CLIENT_NAME, new BuildInfo().version())
                    .build();
        } catch (IllegalArgumentException e) {
            throw usage(e.getMessage());
        }
    }

    private Output output() {
        Map<String, LocalTarget> targets = Map.of(TUNNEL_REF, target);
        return switch (format) {
            case HUMAN ->
                new ConsoleOutput(
                        spec.commandLine().getOut(),
                        spec.commandLine().getErr(),
                        clock,
                        new BuildInfo().version(),
                        targets);
            case JSON -> new JsonLinesOutput(spec.commandLine().getOut(), clock, targets);
        };
    }

    private ParameterException usage(String message) {
        return new ParameterException(spec.commandLine(), message);
    }
}
