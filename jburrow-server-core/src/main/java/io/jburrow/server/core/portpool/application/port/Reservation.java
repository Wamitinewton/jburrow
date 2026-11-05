package io.jburrow.server.core.portpool.application.port;

/**
 * A reserved tunnel port. {@code holder} identifies this reservation, so only its owner can give the port back.
 *
 * @param reclaimed whether the port was held for the same principal when it was reserved, so the reservation took back
 *     a port its owner had a moment ago
 */
public record Reservation(int port, long holder, boolean reclaimed) {}
