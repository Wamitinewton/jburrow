package io.jburrow.cli;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Ctrl-C and {@code SIGTERM} through a JVM shutdown hook. The hook closes the tunnel, waits for the command's exit code,
 * then halts with it: left to itself, the JVM would end a signalled process with {@code 128 + signal} (130 for Ctrl-C),
 * and a deliberate stop is a success.
 *
 * <p>If the command hasn't settled on a code within {@link #TIMEOUT}, the hook returns and the JVM's own status stands.
 */
final class ShutdownHook implements StopSignal {

    static final Duration TIMEOUT = Duration.ofSeconds(3);

    private Thread hook;

    @Override
    public synchronized void install(Runnable stop, CompletionStage<Integer> exitCode) {
        if (hook != null) {
            throw new IllegalStateException("Already installed");
        }
        hook = new Thread(() -> stopThenHalt(stop, exitCode), "jburrow-stop");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    @Override
    public synchronized void uninstall() {
        if (hook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException shuttingDown) {
            // The hook is already running, and halts the JVM once the exit code is known.
        }
        hook = null;
    }

    private static void stopThenHalt(Runnable stop, CompletionStage<Integer> exitCode) {
        stop.run();
        try {
            int code = exitCode.toCompletableFuture().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            System.out.flush();
            System.err.flush();
            Runtime.getRuntime().halt(code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // No exit code: let the JVM finish with its own status.
        }
    }
}
