package io.jburrow.cli.output;

import io.jburrow.client.TunnelStartException;
import io.jburrow.client.tunnel.application.port.TunnelEvents;

/**
 * Where a command reports what happens, one implementation per {@code --output} format. The session's own events
 * arrive through {@link TunnelEvents}, on the client's I/O thread; the outcomes before a session exists arrive here,
 * on the command's thread, and are always the last thing reported.
 *
 * <p>Nothing an implementation writes contains the authtoken: no event carries it.
 */
public sealed interface Output extends TunnelEvents permits ConsoleOutput, JsonLinesOutput {

    /** The session couldn't be established. */
    void onStartFailed(TunnelStartException failure);

    /** The arguments can't be used. The usage error itself is picocli's to print. */
    void onUsageError(String message);

    /** The command failed in a way it doesn't expect. The failure itself is picocli's to print. */
    void onInternalError(Throwable failure);
}
