package io.jburrow.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class VersionCommandTest {

    private final StringWriter out = new StringWriter();

    @Test
    void versionPrintsVersionAndCommit() {
        int exitCode = run("version");

        assertThat(exitCode).isZero();
        assertThat(out.toString().strip()).matches("jburrow \\S+ \\(\\w+\\)");
    }

    @Test
    void versionFlagPrintsTheSameLine() {
        run("version");
        String fromCommand = out.toString();
        out.getBuffer().setLength(0);

        int exitCode = run("--version");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).isEqualTo(fromCommand);
    }

    @Test
    void missingOrUnfilteredValuesFallBackToUnknown() {
        Properties build = new Properties();
        build.setProperty("version", "@project.version@");

        BuildInfo info = new BuildInfo(build, new Properties());

        assertThat(info.describe()).isEqualTo("jburrow unknown (unknown)");
    }

    private int run(String... args) {
        CommandLine cli = JBurrowCli.commandLine();
        cli.setOut(new PrintWriter(out, true));
        return cli.execute(args);
    }
}
