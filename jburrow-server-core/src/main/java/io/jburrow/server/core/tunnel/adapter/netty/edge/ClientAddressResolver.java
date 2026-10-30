package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.jburrow.protocol.control.RemoteAddress;
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
 *
 * <p>A connect request resolves its client once, as a handler that stores it on the channel. A tunnel request is
 * resolved on its own with {@link #visitor}, since the ingress may send a different visitor's address on each request of
 * one connection.
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

    /**
     * The visitor behind {@code request} on {@code channel}: its address by the rules above, and its port when that
     * address is the connection's own peer. A visitor named by {@code X-Forwarded-For} has port 0, which the proxies
     * don't report.
     */
    RemoteAddress visitor(Channel channel, HttpRequest request) {
        InetSocketAddress peer = peerSocket(channel);
        InetAddress client = resolve(peer.getAddress(), request.headers().getAll(FORWARDED_FOR));
        return new RemoteAddress(ipLiteral(client), client.equals(peer.getAddress()) ? peer.getPort() : 0);
    }

    /** Whether {@code channel}'s peer is a trusted proxy, whose forwarding fields the edge believes. */
    boolean trustsPeer(Channel channel) {
        return isTrusted(peer(channel));
    }

    /** An address as an IP literal, without an IPv6 scope, which the protocol doesn't carry. */
    static String ipLiteral(InetAddress address) {
        String literal = address.getHostAddress();
        int scope = literal.indexOf('%');
        return scope < 0 ? literal : literal.substring(0, scope);
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
        return peerSocket(channel).getAddress();
    }

    private static InetSocketAddress peerSocket(Channel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress socket && socket.getAddress() != null) {
            return socket;
        }
        throw new IllegalStateException("The edge only accepts IP connections: " + channel.remoteAddress());
    }
}
