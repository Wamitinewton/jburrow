package io.jburrow.client;

import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.control.RegisteredTunnel;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** An established session, as {@link JBurrowClient#start()} returns it. Safe to use from any thread. */
public interface TunnelHandle {

    /** The id the server gave the session. Not a secret: it appears in the server's logs. */
    String sessionId();

    /** The tunnels the server created, in the order they were asked for, with their public URLs. */
    List<RegisteredTunnel> tunnels();

    /** Completes once, when the session has ended, with the reason. Never completes exceptionally. */
    CompletionStage<TunnelClosed> closed();

    /**
     * Ends the session on purpose: open streams are reset with {@code CANCELLED}, then a WebSocket close with status
     * {@code 1000}. Returns at once; {@link #closed()} completes with {@code USER_CLOSED} once the server answers, or
     * after 2 seconds. Does nothing once the session is ending.
     */
    void close();
}
