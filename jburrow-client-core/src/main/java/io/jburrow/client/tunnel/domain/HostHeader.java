package io.jburrow.client.tunnel.domain;

/** What an HTTP tunnel sends the local service as {@code host}. */
public enum HostHeader {
    /** The {@code host} the visitor asked for, as the request head carries it. */
    KEEP,
    /** The local target's {@code host:port}, for a local service that only answers its own name. */
    REWRITE
}
