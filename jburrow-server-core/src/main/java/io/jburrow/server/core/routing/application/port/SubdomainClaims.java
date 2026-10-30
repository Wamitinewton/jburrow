package io.jburrow.server.core.routing.application.port;

import io.jburrow.protocol.control.SubdomainName;

/**
 * Claims names for tunnels. Used by registration. Thread-safe.
 *
 * @param <P> who claims a name; a held name is kept for the same principal
 * @param <T> the tunnel a name routes to
 */
public interface SubdomainClaims<P, T> {

    /**
     * Claims a random name nobody holds.
     *
     * @throws IllegalStateException if 8 draws in a row were taken, which at this scale means a bug, not a full space
     */
    SubdomainName claimRandom(P principal, T tunnel);

    /**
     * Claims {@code name}, if it is unclaimed, its hold has expired, or it is held for {@code principal}.
     *
     * @throws ClaimRefused {@link ClaimRefused.Reason#FORBIDDEN} if the blocklist refuses it,
     *     {@link ClaimRefused.Reason#UNAVAILABLE} if someone else has it
     */
    SubdomainName claim(SubdomainName name, P principal, T tunnel);

    /**
     * Ends {@code tunnel}'s claim on {@code name} and starts its hold. Does nothing if {@code tunnel} no longer has an
     * active claim on it, so a late release can't end someone else's claim.
     */
    void release(SubdomainName name, T tunnel);
}
