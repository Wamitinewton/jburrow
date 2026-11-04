package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.jburrow.server.core.tunnel.application.HandshakeTicket;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Takes over a connection once its WebSocket upgrade completes. Called on the channel's event loop, with the upgrade
 * handlers gone and a {@code WebSocketFrameAggregator} last in the pipeline.
 *
 * <p>The edge releases {@code ticket} when the channel closes, so an implementation never releases it itself.
 */
@FunctionalInterface
public interface SessionInitializer {

    void initialize(Channel channel, String subprotocol, InetAddress clientAddress, HandshakeTicket ticket);

    /**
     * Starts the drain, steps 2 to 5 of {@code docs/phase-4-staying-connected/ARCHITECTURE.md} §The drain: a session in
     * its handshake ends with {@code SERVER_SHUTTING_DOWN} at once, an established one opens no new stream, closes its
     * TCP tunnel listeners, and is sent {@code GOAWAY} if it has {@code resume}. A session that begins after this is
     * called ends at once. Called once, after the listener has closed.
     *
     * @return completes, never exceptionally, when no session has an open stream or {@code timeout} has passed,
     *     whichever is first
     */
    default CompletableFuture<Drained> drain(Duration timeout) {
        return CompletableFuture.completedFuture(Drained.NONE);
    }

    /**
     * Ends every session because the server is stopping, phase 1's way: open streams reset with {@code CANCELLED}, then
     * {@code ERROR SERVER_SHUTTING_DOWN}. Called once, after {@link #drain}.
     *
     * @return completes, never exceptionally, once every session has ended and given back what it took, its names and
     *     ports on hold, with the number of streams that were still open and were cut. The edge closes the connections
     *     still open if this takes too long.
     */
    default CompletableFuture<Integer> end() {
        return CompletableFuture.completedFuture(0);
    }

    /**
     * What a drain started with, for its log line.
     *
     * @param sessions established sessions told to drain
     * @param openStreams streams those sessions had open when the drain began
     */
    record Drained(int sessions, int openStreams) {

        public static final Drained NONE = new Drained(0, 0);
    }

    /** Ends every upgraded connection with WebSocket status 1011, for an edge that has no session engine yet. */
    static SessionInitializer unavailable() {
        return (channel, subprotocol, clientAddress, ticket) -> channel.writeAndFlush(
                        new CloseWebSocketFrame(WebSocketCloseStatus.INTERNAL_SERVER_ERROR, "Sessions are unavailable"))
                .addListener(ChannelFutureListener.CLOSE);
    }
}
