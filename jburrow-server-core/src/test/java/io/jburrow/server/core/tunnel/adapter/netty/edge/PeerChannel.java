package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** An {@link EmbeddedChannel} connected from a real socket address, as an accepted edge connection is. */
final class PeerChannel extends EmbeddedChannel {

    private final InetSocketAddress remote;

    PeerChannel(InetSocketAddress remote, ChannelHandler... handlers) {
        super(false, true, handlers);
        this.remote = remote;
        try {
            register();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remote;
    }
}
