package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.netty.channel.Channel;
import java.util.List;

/** The connections an {@link EdgeServer} has accepted and not yet closed, for tests in other packages. */
public final class EdgeConnections {

    private EdgeConnections() {}

    public static List<Channel> of(EdgeServer edge) {
        return List.copyOf(edge.connections());
    }
}
