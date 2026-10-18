package io.jburrow.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "version", description = "Print the version and commit of this build.")
final class VersionCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getOut().println(new BuildInfo().describe());
        return 0;
    }
}
