package io.jburrow.server.core.portpool.application.port;

/** How many tunnel ports are in each state. */
public record PortPoolSnapshot(int free, int reserved, int quarantined) {}
