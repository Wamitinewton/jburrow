package io.jburrow.cli;

import io.jburrow.client.tunnel.domain.HostHeader;
import io.jburrow.client.tunnel.domain.LocalTarget;
import io.jburrow.client.tunnel.domain.TunnelSpec;
import io.jburrow.protocol.control.SubdomainName;
import java.time.Clock;
import java.util.Locale;
import java.util.function.UnaryOperator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code jburrow http [host:]port}: opens one HTTP tunnel, prints its public URL and a line per request, until the
 * session ends.
 */
@Command(
        name = "http",
        mixinStandardHelpOptions = true,
        versionProvider = BuildInfo.class,
        description = "Expose a local HTTP service at a public URL on the JBurrow server.")
final class HttpCommand extends TunnelCommand {

    /** {@code --host-header}'s values. Only {@code rewrite} until a host of the user's choosing arrives (v1). */
    enum HostHeaderOption {
        REWRITE;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Option(
            names = "--subdomain",
            paramLabel = "<name>",
            description = "Ask for this name: <name>.<the server's domain> (default: a random name).")
    private String subdomain;

    @Option(
            names = "--host-header",
            paramLabel = "rewrite",
            description = "rewrite: send the local service its own host:port as Host, instead of the public name.")
    private HostHeaderOption hostHeader;

    HttpCommand() {
        this(System::getenv, new ShutdownHook(), Clock.systemDefaultZone());
    }

    HttpCommand(UnaryOperator<String> environment, StopSignal stopSignal, Clock clock) {
        super(environment, stopSignal, clock);
    }

    @Override
    TunnelSpec tunnel(LocalTarget target) {
        TunnelSpec tunnel = TunnelSpec.http(TUNNEL_REF, target);
        if (subdomain != null) {
            tunnel = tunnel.subdomain(subdomainName(subdomain));
        }
        if (hostHeader == HostHeaderOption.REWRITE) {
            tunnel = tunnel.hostHeader(HostHeader.REWRITE);
        }
        return tunnel;
    }

    private SubdomainName subdomainName(String name) {
        return SubdomainName.parse(name.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> usage("'" + name + "' is not a valid subdomain: use 3 to 63 letters, digits and"
                        + " hyphens, without a leading, trailing or doubled hyphen."));
    }
}
