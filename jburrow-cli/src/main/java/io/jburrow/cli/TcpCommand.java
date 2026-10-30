package io.jburrow.cli;

import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import java.time.Clock;
import java.util.function.UnaryOperator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code jburrow tcp [host:]port}: opens one TCP tunnel and reports it until the session ends. */
@Command(
        name = "tcp",
        mixinStandardHelpOptions = true,
        versionProvider = BuildInfo.class,
        description = "Expose a local TCP port through a public port on the JBurrow server.")
final class TcpCommand extends TunnelCommand {

    @Option(
            names = "--remote-port",
            paramLabel = "<port>",
            description = "Ask for this public port (default: any free port).")
    private Integer remotePort;

    TcpCommand() {
        this(System::getenv, new ShutdownHook(), Clock.systemDefaultZone());
    }

    TcpCommand(UnaryOperator<String> environment, StopSignal stopSignal, Clock clock) {
        super(environment, stopSignal, clock);
    }

    @Override
    TunnelSpec tunnel(LocalTarget target) {
        TunnelSpec tunnel = TunnelSpec.tcp(TUNNEL_REF, target);
        return remotePort == null ? tunnel : tunnel.remotePort(remotePort);
    }
}
