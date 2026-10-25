package io.jburrow.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** The {@code jburrow} command. Without a subcommand it prints usage. */
@Command(
        name = "jburrow",
        mixinStandardHelpOptions = true,
        versionProvider = BuildInfo.class,
        subcommands = {TcpCommand.class, VersionCommand.class},
        description = "Expose a local port through a JBurrow server.")
public final class JBurrowCli implements Runnable {

    @Spec
    private CommandSpec spec;

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    static CommandLine commandLine() {
        return commandLine(CommandLine.defaultFactory());
    }

    /** The command tree, with its subcommands made by {@code factory}. */
    static CommandLine commandLine(CommandLine.IFactory factory) {
        return new CommandLine(new JBurrowCli(), factory)
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExitCodeExceptionMapper(ExitCodes.EXCEPTIONS);
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
