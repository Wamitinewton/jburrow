package io.jburrow.server.core.portpool.application.port;

import java.time.Instant;
import java.util.List;

/**
 * The port pool's claims, read and seeded by whatever carries them across a restart. Thread-safe.
 *
 * @param <P> who a port is claimed for
 */
public interface PortHolds<P> {

    /** Every reserved port, and every hold that hasn't ended by {@code now}. */
    List<PortClaimSnapshot<P>> snapshot(Instant now);

    /**
     * Holds {@code port} for {@code principal} until {@code until}, if the port is in the range, nobody has it reserved
     * or held, and {@code until} hasn't passed. Never replaces a claim.
     *
     * @return whether the hold was added; {@code false} for a port outside the configured range, among others
     */
    boolean seedHold(int port, P principal, Instant until);

    /** Changes whenever the claims may have changed; equal values mean they haven't. */
    long version();
}
