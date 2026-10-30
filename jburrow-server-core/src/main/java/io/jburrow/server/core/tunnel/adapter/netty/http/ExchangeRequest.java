package io.jburrow.server.core.tunnel.adapter.netty.http;

import io.jburrow.protocol.control.RemoteAddress;
import io.jburrow.protocol.control.SubdomainName;
import io.netty.handler.codec.http.HttpRequest;
import java.util.Objects;

/**
 * A request head the dispatcher has routed to a tunnel host, passed to the {@link ExchangeHandler} in place of the
 * bare head. Its body follows as the codec's {@code HttpContent}, ending with a {@code LastHttpContent}.
 *
 * @param head the decoded head; holds no buffer
 * @param host the host the visitor asked for: lower-case, without a port
 * @param name the tunnel name {@code host} carries
 * @param visitor who sent the request, by the edge's trusted proxies: its address, and its port when that address is the
 *     connection's own peer, else 0
 * @param viaTrustedProxy whether the connection's peer is a trusted proxy, whose forwarding fields the edge believes
 */
public record ExchangeRequest(
        HttpRequest head, String host, SubdomainName name, RemoteAddress visitor, boolean viaTrustedProxy) {

    public ExchangeRequest {
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(visitor, "visitor");
    }
}
