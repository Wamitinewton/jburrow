package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.jburrow.server.core.tunnel.application.HandshakeTicket;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.net.InetAddress;

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
     * Ends every session because the server is stopping. Called once, after the listener has closed, and returns
     * without waiting: the edge waits for the connections to close.
     */
    default void shutDown() {}

    /** Ends every upgraded connection with WebSocket status 1011, for an edge that has no session engine yet. */
    static SessionInitializer unavailable() {
        return (channel, subprotocol, clientAddress, ticket) -> channel.writeAndFlush(
                        new CloseWebSocketFrame(WebSocketCloseStatus.INTERNAL_SERVER_ERROR, "Sessions are unavailable"))
                .addListener(ChannelFutureListener.CLOSE);
    }
}
