package io.jburrow.server.core.routing.application.port;

import io.jburrow.protocol.control.SubdomainName;
import java.time.Instant;
import java.util.List;

/**
 * The route table's claims, read and seeded by whatever carries them across a restart. Thread-safe.
 *
 * @param <P> who a name is claimed for
 */
public interface ClaimSnapshots<P> {

    /** Every active claim, and every hold that hasn't ended by {@code now}. */
    List<ClaimSnapshot<P>> snapshot(Instant now);

    /**
     * Holds {@code name} for {@code principal} until {@code until}, if nobody has an active claim or a hold on it and
     * {@code until} hasn't passed. Never replaces a claim.
     *
     * @return whether the hold was added
     */
    boolean seedHold(SubdomainName name, P principal, Instant until);

    /** Changes whenever the claims may have changed; equal values mean they haven't. */
    long version();
}
