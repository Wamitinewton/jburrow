package io.jburrow.server.core.lease.domain;

/** What a lease keeps: a subdomain name, or a TCP port. */
public enum LeaseKind {
    NAME,
    PORT
}
