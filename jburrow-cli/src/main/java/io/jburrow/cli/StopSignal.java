package io.jburrow.cli;

import java.util.concurrent.CompletionStage;

/** How a running command hears that the user wants it to stop: Ctrl-C or {@code SIGTERM} in a real process. */
interface StopSignal {

    /**
     * Arranges for {@code stop} to run once, on another thread, when the user asks the process to end. {@code exitCode}
     * completes with the command's exit code once the command has reported its final event.
     */
    void install(Runnable stop, CompletionStage<Integer> exitCode);

    /** The command is finished; a later stop request does nothing here. */
    void uninstall();
}
