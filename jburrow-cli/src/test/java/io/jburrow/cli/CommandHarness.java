package io.jburrow.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the real command line, {@link JBurrowCli}'s, with the tunnel commands given this harness's environment, a stop
 * signal the test triggers, and a fixed clock, and captures stdout and stderr.
 */
final class CommandHarness implements AutoCloseable {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private final Map<String, String> environment = new HashMap<>();
    private final ManualStop stop = new ManualStop();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final Clock clock;

    CommandHarness(Clock clock, String authtoken) {
        this.clock = clock;
        environment.put(TunnelCommand.AUTHTOKEN_VARIABLE, authtoken);
    }

    Map<String, String> environment() {
        return environment;
    }

    ManualStop stop() {
        return stop;
    }

    ExecutorService threads() {
        return threads;
    }

    String out() {
        return out.toString();
    }

    String err() {
        return err.toString();
    }

    int run(String... args) {
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                if (type == TcpCommand.class) {
                    return type.cast(new TcpCommand(environment::get, stop, clock));
                }
                if (type == HttpCommand.class) {
                    return type.cast(new HttpCommand(environment::get, stop, clock));
                }
                return CommandLine.defaultFactory().create(type);
            }
        };
        CommandLine cli = JBurrowCli.commandLine(factory);
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        return cli.execute(args);
    }

    Future<Integer> runAsync(String... args) {
        return threads.submit(() -> run(args));
    }

    int exitCode(Future<Integer> command) throws Exception {
        return command.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    List<String> lines() {
        return out().lines().toList();
    }

    List<JsonNode> jsonLines() {
        return lines().stream().map(JSON::readTree).toList();
    }

    void awaitOutput(String text) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!out().contains(text)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("stdout didn't contain '" + text + "' within " + TIMEOUT + ":\n" + out);
            }
            Thread.sleep(5);
        }
    }

    @Override
    public void close() {
        threads.shutdownNow();
    }

    /** The command's stop path, run on request instead of by a signal. */
    static final class ManualStop implements StopSignal {

        private final CountDownLatch installed = new CountDownLatch(1);
        private volatile Runnable stop;
        volatile CompletionStage<Integer> exitCode;
        volatile boolean uninstalled;
        volatile boolean failOnInstall;

        @Override
        public void install(Runnable stop, CompletionStage<Integer> exitCode) {
            if (failOnInstall) {
                throw new IllegalStateException("The stop signal can't be installed");
            }
            this.stop = stop;
            this.exitCode = exitCode;
            installed.countDown();
        }

        @Override
        public void uninstall() {
            uninstalled = true;
        }

        void awaitInstalled() throws InterruptedException {
            assertThat(installed.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .as("the command installed its stop signal")
                    .isTrue();
        }

        void request() {
            stop.run();
        }
    }
}
