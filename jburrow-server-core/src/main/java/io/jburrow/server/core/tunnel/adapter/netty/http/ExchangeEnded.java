package io.jburrow.server.core.tunnel.adapter.netty.http;

/**
 * Fired from the head of an edge connection's pipeline, on its event loop, when an exchange is over and the
 * connection may carry the next request: the response is flushed and the request body has been read to its end. An
 * exchange that can't leave the connection reusable closes it instead and fires nothing.
 */
public enum ExchangeEnded {
    INSTANCE
}
