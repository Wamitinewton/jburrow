package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Works out the address of the client behind a connection and stores it on the channel.
 *
 * <p>{@code X-Forwarded-For} is believed only when the TCP peer is a trusted proxy, and then only as far as the
 * proxies that wrote it are trusted: walking from the right, the first entry that isn't a trusted proxy is the address
 * the last trusted proxy saw. Anything else would let a client choose the address it is logged and, from phase 6,
 * rate-limited under. Entries are IP literals; a name is never resolved.
 */
@Sharable
final class ClientAddressResolver extends ChannelInboundHandlerAdapter {

    static final AttributeKey<InetAddress> CLIENT_ADDRESS =
            AttributeKey.valueOf(ClientAddressResolver.class, "clientAddress");

    static final String FORWARDED_FOR = "X-Forwarded-For";

    private final List<Cidr> trustedProxies;

    ClientAddressResolver(List<Cidr> trustedProxies) {
        this.trustedProxies = List.copyOf(trustedProxies);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest request) {
            InetAddress client = resolve(peer(ctx.channel()), request.headers().getAll(FORWARDED_FOR));
            ctx.channel().attr(CLIENT_ADDRESS).set(client);
        }
        ctx.fireChannelRead(msg);
    }

    /** The resolved client address, or the TCP peer if no request has been resolved on this channel yet. */
    static InetAddress clientAddress(Channel channel) {
        InetAddress resolved = channel.attr(CLIENT_ADDRESS).get();
        return resolved != null ? resolved : peer(channel);
    }

    InetAddress resolve(InetAddress peer, List<String> forwardedForHeaders) {
        Objects.requireNonNull(peer, "peer");
        if (!isTrusted(peer)) {
            return peer;
        }
        List<String> entries = new ArrayList<>();
        for (String header : forwardedForHeaders) {
            for (String entry : header.split(",", -1)) {
                entries.add(entry.strip());
            }
        }
        if (entries.isEmpty()) {
            return peer;
        }
        InetAddress leftmost = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Optional<InetAddress> hop = Cidr.parseAddress(entries.get(i));
            if (hop.isEmpty()) {
                return peer;
            }
            if (!isTrusted(hop.get())) {
                return hop.get();
            }
            leftmost = hop.get();
        }
        return leftmost;
    }

    private boolean isTrusted(InetAddress address) {
        for (Cidr proxy : trustedProxies) {
            if (proxy.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static InetAddress peer(Channel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress socket && socket.getAddress() != null) {
            return socket.getAddress();
        }
        throw new IllegalStateException("The edge only accepts IP connections: " + channel.remoteAddress());
    }
}
