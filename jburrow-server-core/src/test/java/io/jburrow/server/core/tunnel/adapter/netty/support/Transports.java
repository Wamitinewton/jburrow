package io.jburrow.server.core.tunnel.adapter.netty.support;

import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.netty.channel.epoll.Epoll;
import java.util.ArrayList;
import java.util.List;

/**
 * The transports a byte-path integration test runs on: epoll where its native library loads, and always NIO. Both,
 * because the one-byte carry exists for epoll's sake and only a test on epoll proves it.
 */
public final class Transports {

    private Transports() {}

    /** For {@code @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")}. */
    public static List<EdgeTransport> available() {
        List<EdgeTransport> transports = new ArrayList<>();
        if (Epoll.isAvailable()) {
            transports.add(EdgeTransport.EPOLL);
        }
        transports.add(EdgeTransport.NIO);
        return transports;
    }
}
