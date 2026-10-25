package io.jburrow.client.tunnel.adapter.netty;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Resolves a host name to its addresses, in the order to try them. Blocks; never called on an event loop. */
@FunctionalInterface
interface HostResolver {

    /** The operating system's resolver, hosts file included, through the JDK. */
    HostResolver SYSTEM = host -> List.of(InetAddress.getAllByName(host));

    List<InetAddress> resolve(String host) throws UnknownHostException;
}
