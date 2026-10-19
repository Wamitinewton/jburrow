package io.jburrow.server.core.portpool.application.port;

/** A reserved tunnel port. {@code holder} identifies this reservation, so only its owner can give the port back. */
public record Reservation(int port, long holder) {}
