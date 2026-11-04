package io.jburrow.client;

import io.jburrow.client.tunnel.domain.TunnelClosed;
import io.jburrow.protocol.control.RegisteredTunnel;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * A started client, as {@link JBurrowClient#start()} returns it once its first session is established. The client may
 * run further sessions after that one, reconnecting as its {@link ReconnectPolicy} says; the handle always describes the
 * current one. Safe to use from any thread.
 */
public interface TunnelHandle {

    /**
     * The id the server gave the current session, or the last one while the client reconnects. Not a secret: it appears
     * in the server's logs.
     */
    String sessionId();

    /**
     * The tunnels the server created for the current session, or the last one while the client reconnects, in the
     * order they were asked for, with their public URLs: {@code https://<name>.<domain>} (or {@code http://}) for an
     * HTTP tunnel, {@code tcp://<host>:<port>} for a TCP one. A reconnect asks for the same URLs, but may get another
     * for a name or port the server chose and someone else took in between.
     */
    List<RegisteredTunnel> tunnels();

    /**
     * Completes once, when the client stops for good, with the reason the last session ended or the last attempt
     * failed. Never completes exceptionally.
     */
    CompletionStage<TunnelClosed> closed();

    /**
     * Stops the client on purpose: a scheduled reconnect is cancelled, and the current session's open streams are reset
     * with {@code CANCELLED}, then a WebSocket close with status {@code 1000}. Returns at once; {@link #closed()}
     * completes with {@code USER_CLOSED} once the server answers, or after 2 seconds. Does nothing once the client is
     * stopping.
     */
    void close();
}
